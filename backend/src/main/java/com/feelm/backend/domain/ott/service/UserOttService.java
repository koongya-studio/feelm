package com.feelm.backend.domain.ott.service;

import com.feelm.backend.domain.ott.dto.response.UserOttResponse;
import com.feelm.backend.domain.ott.entity.UserOtt;
import com.feelm.backend.domain.ott.repository.OttRepository;
import com.feelm.backend.domain.ott.repository.UserOttRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ConstraintViolations;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 구독 OTT 조회·설정(API-026·027).
 *
 * <p>구독 목록은 추천 필터(FR-12)와 영화 상세의 OTT 정렬(FR-16)이 읽는 값이라 설정 결과가 요청과 정확히 같아야 한다. 설정 경로가 전체 교체뿐이므로 두
 * 요청이 삭제와 삽입을 교차 실행하면 어느 쪽 요청과도 다른 합집합이 남을 수 있다. 이를 막기 위해 계정 행을 잠그고 교체한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserOttService {

    private final UserOttRepository userOttRepository;
    private final OttRepository ottRepository;
    private final UserRepository userRepository;

    /**
     * 구독 중인 OTT 식별자를 읽는다(API-026). 설정한 적이 없으면 빈 배열이다.
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}
     */
    public UserOttResponse getMyOtts(long userId) {
        userRepository
                .findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return new UserOttResponse(userOttRepository.findOttIdsByUserId(userId));
    }

    /**
     * 구독 OTT를 전체 교체한다(API-027). 멱등이며 빈 목록이면 전체 해제다.
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}, 존재하지 않는 OTT 식별자가 있으면 {@code
     *     VALIDATION_FAILED}
     */
    @Transactional
    public UserOttResponse replaceMyOtts(long userId, List<Long> requestedOttIds) {
        // 같은 식별자를 두 번 보내도 한 행이므로 저장 전에 정리한다. 응답 순서는 조회(API-026)와 같게 오름차순으로 맞춘다.
        List<Long> ottIds = requestedOttIds.stream().distinct().sorted().toList();
        // 활성 계정 판정과 직렬화를 한 번의 조회로 처리한다. 잠금은 이 트랜잭션의 삭제·삽입 구간에만 걸리고 외부 호출을 포함하지 않는다.
        userRepository
                .findByIdForUpdate(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!ottIds.isEmpty() && ottRepository.countByIdIn(ottIds) != ottIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        userOttRepository.deleteByUserId(userId);
        try {
            // 존재 검증과 삽입 사이에 OTT가 사라지면 FK 제약이 최종 판정을 내린다. 제약 위반이 여기까지 올라오도록
            // 저장 시점에 flush 하고, 그 제약일 때만 사전 검사와 같은 의미인 VALIDATION_FAILED로 바꾼다.
            userOttRepository.saveAllAndFlush(
                    ottIds.stream().map(ottId -> UserOtt.of(userId, ottId)).toList());
        } catch (DataIntegrityViolationException exception) {
            throw ConstraintViolations.ottReferenceOf(exception);
        }
        return new UserOttResponse(ottIds);
    }
}
