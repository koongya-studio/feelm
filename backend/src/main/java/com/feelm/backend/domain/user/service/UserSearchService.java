package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.NicknamePolicy;
import com.feelm.backend.domain.user.dto.response.SearchedUserResponse;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.domain.user.repository.UserSearchQueryRepository;
import com.feelm.backend.domain.user.repository.UserSearchQueryRepository.SearchedUser;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.response.CursorPageResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 닉네임으로 회원을 검색한다(API-019). 파티 초대 후보 선택에 쓴다(FR-20).
 *
 * <p>읽기 전용 유스케이스다. 결과에서 빼야 하는 대상은 셋이며 모두 질의 조건으로 건다.
 *
 * <ul>
 *   <li>본인 — 자기 자신을 초대할 수 없다
 *   <li>양방향 차단 상대 — 한쪽만 차단해도 서로 보이지 않는다(FR-39)
 *   <li>활성이 아닌 계정 — 탈퇴·미인증 계정은 존재를 드러내지 않는다
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSearchService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final UserSearchQueryRepository searchQueryRepository;
    private final UserBlockFilter blockFilter;
    private final UserRepository userRepository;

    /**
     * 닉네임 접두가 일치하는 활성 회원을 정규화 닉네임 오름차순으로 조회한다.
     *
     * @param nickname 검색어. 정규화(공백 제거·소문자) 후 {@code 2~10자}여야 한다
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}, 검색어·size가 계약을 벗어나거나 커서 형식이
     *     깨졌으면 {@code VALIDATION_FAILED}
     */
    public CursorPageResponse<SearchedUserResponse> search(
            long requesterId, String nickname, String encodedCursor, Integer requestedSize) {
        requireActiveRequester(requesterId);
        String normalized = normalizedNicknameOrReject(nickname);
        int size = pageSizeOrReject(requestedSize);
        UserSearchCursor cursor = UserSearchCursor.decode(encodedCursor);
        if (NicknamePolicy.containsControlCharacter(normalized)) {
            // 어떤 닉네임에도 담길 수 없는 문자다. 계약대로 빈 결과로 답한다. 그대로 질의에 넘기면
            // NUL을 담지 못하는 PostgreSQL text 파라미터에 걸려 클라이언트 입력이 500이 된다.
            return new CursorPageResponse<>(List.of(), null, false);
        }
        String prefix = escapeLikeWildcards(normalized);
        Set<Long> excludedUserIds = excludedUserIds(requesterId);

        // 다음 페이지 존재 여부는 한 건 더 읽어 판단한다. 별도 count 질의를 돌리지 않는다.
        int limit = size + 1;
        List<SearchedUser> rows =
                cursor == null
                        ? searchQueryRepository.findFirstPage(prefix, excludedUserIds, limit)
                        : searchQueryRepository.findPageAfter(
                                prefix, excludedUserIds, cursor.nickname(), cursor.userId(), limit);

        boolean hasNext = rows.size() > size;
        List<SearchedUser> page = hasNext ? rows.subList(0, size) : rows;
        List<SearchedUserResponse> items =
                page.stream()
                        .map(
                                row ->
                                        new SearchedUserResponse(
                                                row.userId(), row.nickname(), row.visibility()))
                        .toList();
        if (!hasNext) {
            return new CursorPageResponse<>(items, null, false);
        }
        SearchedUser last = page.getLast();
        String nextCursor = new UserSearchCursor(last.sortKey(), last.userId()).encode();
        return new CursorPageResponse<>(items, nextCursor, true);
    }

    /**
     * 검색어를 질의에 넣을 수 있는 접두 패턴으로 바꾼다.
     *
     * <p>정규화는 닉네임 유일 인덱스와 같은 규칙이라 "가입할 때 중복이던 닉네임이 검색되지 않는" 불일치가 생기지 않는다. 길이는 정규화 <b>후</b> 값으로 재는데,
     * 공백만 다른 검색어가 통과 여부까지 달라지면 안 되기 때문이다. 상한 {@code MAX_LENGTH}를 넘는 검색어는 어떤 닉네임과도 일치할 수 없다.
     *
     * <p>문자 종류는 제한하지 않는다. 계약이 요구하는 제약은 "2자 이상"뿐이고, 닉네임에 쓸 수 없는 문자가 섞인 검색어는 오류가 아니라 빈 결과로 답하는 편이 입력
     * 중인 화면에 맞다. 다만 제어문자는 질의에 넘기기 전에 호출자가 걸러낸다.
     */
    private static String normalizedNicknameOrReject(String nickname) {
        String normalized = NicknamePolicy.normalize(nickname);
        if (normalized == null
                || normalized.length() < NicknamePolicy.MIN_LENGTH
                || normalized.length() > NicknamePolicy.MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    /**
     * {@code LIKE} 메타문자를 리터럴로 만든다.
     *
     * <p>{@code _}는 닉네임에 실제로 쓰이는 문자라({@code NicknamePolicy}) 한 글자 와일드카드로 해석되면 {@code ab_}가 {@code
     * abc}까지 찾아낸다. 이스케이프 문자 자신을 먼저 치환해야 뒤이어 붙인 이스케이프가 다시 이스케이프되지 않는다.
     */
    private static String escapeLikeWildcards(String value) {
        String escapeCharacter = String.valueOf(UserSearchQueryRepository.LIKE_ESCAPE);
        return value.replace(escapeCharacter, escapeCharacter + escapeCharacter)
                .replace("%", escapeCharacter + "%")
                .replace("_", escapeCharacter + "_");
    }

    /** size 계약은 차단 목록(API-022)과 같다. 계정 도메인의 커서 목록이 서로 다른 규칙을 쓰지 않게 한다. */
    private static int pageSizeOrReject(Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return size;
    }

    /**
     * 질의에서 제외할 사용자 id. 본인을 항상 포함하므로 결코 비지 않는다.
     *
     * <p>비어 있으면 {@code in ()}이 되어 SQL이 깨진다. 차단 상대는 후보를 미리 좁힐 수 없는 목록 조회의 방식대로 전부 가져온다(컨벤션 11장). 반환
     * 크기가 차단 건수에 비례하므로, 한 사용자의 차단이 권장 상한 1,000건을 넘길 만큼 늘어나면 제외 방식을 다시 본다.
     */
    private Set<Long> excludedUserIds(long requesterId) {
        Set<Long> excluded = new LinkedHashSet<>(blockFilter.blockedCounterpartIds(requesterId));
        excluded.add(requesterId);
        return excluded;
    }

    /**
     * 요청자가 활성 계정인지 확인한다.
     *
     * <p>Access Token은 계정 상태를 담지 않아 탈퇴·미인증 계정도 잔여 수명 동안 서명이 유효하다. 내
     * 프로필(API-014·015)·차단(API-020~022)과 같은 방향으로 막는다.
     */
    private void requireActiveRequester(long requesterId) {
        userRepository
                .findById(requesterId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
