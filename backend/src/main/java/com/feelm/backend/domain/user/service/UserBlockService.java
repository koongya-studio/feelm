package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.dto.response.BlockedUserResponse;
import com.feelm.backend.domain.user.dto.response.UserBlockResponse;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.entity.UserBlock;
import com.feelm.backend.domain.user.repository.UserBlockRepository;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ConstraintViolations;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 차단 등록·해제·목록 조회(API-020~022).
 *
 * <p>쓰기 메서드는 <b>최상위 트랜잭션에서만</b> 호출한다. {@link #block(long, long)}은 실패를 {@code BusinessException}으로
 * 바꿔 던지므로 참여 트랜잭션이 rollback-only로 표시되고, 호출자가 예외를 잡아 계속 진행하면 커밋 시점에 {@code
 * UnexpectedRollbackException}이 난다. {@link #unblock(long, long)}이 쓰는 벌크 삭제는 EntityManager 전체를
 * flush·clear 하므로 상위 트랜잭션이 들고 있던 엔티티가 detach 된다. 차단을 다른 작업과 한 트랜잭션으로 묶어야 하면(API-020 처리 규칙의 파티 연계)
 * 선조회·잠금 기반으로 조정 서비스를 따로 설계한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserBlockService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserBlockRepository blockRepository;
    private final UserRepository userRepository;

    /**
     * 다른 사용자를 차단한다(API-020).
     *
     * <p>차단 대상이 활성 계정이 아니면 존재 자체를 알리지 않고 {@code RESOURCE_NOT_FOUND}로 응답한다(공통 규약, NFR-07).
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}, 자기 자신이면 {@code
     *     CANNOT_BLOCK_SELF}, 대상이 없거나 활성 계정이 아니면 {@code RESOURCE_NOT_FOUND}, 이미 차단했으면 {@code
     *     ALREADY_BLOCKED}
     */
    @Transactional
    public UserBlockResponse block(long blockerId, long targetUserId) {
        User blocker = activeRequesterOrReject(blockerId);
        if (blockerId == targetUserId) {
            throw new BusinessException(ErrorCode.CANNOT_BLOCK_SELF);
        }
        User target =
                userRepository
                        .findById(targetUserId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (blockRepository.existsByBlockerIdAndBlockedId(blockerId, targetUserId)) {
            throw new BusinessException(ErrorCode.ALREADY_BLOCKED);
        }
        try {
            // 선조회와 저장 사이에 같은 차단이 들어오면 UNIQUE 제약이 최종 판정을 내린다. 제약 위반이 여기까지
            // 올라오도록 저장 시점에 flush 하고, 중복 제약일 때만 같은 의미인 ALREADY_BLOCKED로 바꾼다.
            UserBlock saved = blockRepository.saveAndFlush(UserBlock.of(blocker, target));
            return UserBlockResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            throw ConstraintViolations.blockDuplicationOf(exception);
        }
    }

    /**
     * 차단을 해제한다(API-021).
     *
     * <p>완전 멱등이다. 차단 관계가 없거나 대상이 존재하지 않아도 오류를 내지 않으며, 대상 사용자의 존재 여부가 응답으로 구분되지 않는다. 역방향 차단 행은 남긴다.
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}
     */
    @Transactional
    public void unblock(long blockerId, long targetUserId) {
        activeRequesterOrReject(blockerId);
        blockRepository.deleteByBlockerIdAndBlockedId(blockerId, targetUserId);
    }

    /**
     * 내가 차단한 사용자 목록을 최신순으로 조회한다(API-022). 나를 차단한 사용자는 포함하지 않는다.
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}, size가 1~50을 벗어나거나 커서 형식이 깨졌으면
     *     {@code VALIDATION_FAILED}
     */
    public CursorPageResponse<BlockedUserResponse> list(
            long blockerId, String encodedCursor, Integer requestedSize) {
        activeRequesterOrReject(blockerId);
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        UserBlockCursor cursor = UserBlockCursor.decode(encodedCursor);
        Limit limit = Limit.of(size + 1);
        List<UserBlock> rows =
                cursor == null
                        ? blockRepository.findPage(blockerId, limit)
                        : blockRepository.findPageBefore(
                                blockerId, cursor.createdAt(), cursor.blockId(), limit);
        boolean hasNext = rows.size() > size;
        List<UserBlock> page = hasNext ? rows.subList(0, size) : rows;
        List<BlockedUserResponse> items = page.stream().map(BlockedUserResponse::from).toList();
        if (!hasNext) {
            return new CursorPageResponse<>(items, null, false);
        }
        UserBlock last = page.getLast();
        String nextCursor = new UserBlockCursor(last.getCreatedAt(), last.getId()).encode();
        return new CursorPageResponse<>(items, nextCursor, true);
    }

    /**
     * 요청자가 활성 계정인지 확인한다.
     *
     * <p>Access Token은 계정 상태를 담지 않아 탈퇴·미인증 계정도 잔여 수명 동안 서명이 유효하다. 내 프로필(API-014·015)·비밀번호
     * 변경(API-012)과 같은 방향으로 막는다.
     */
    private User activeRequesterOrReject(long userId) {
        Optional<User> found = userRepository.findById(userId);
        return found.filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
