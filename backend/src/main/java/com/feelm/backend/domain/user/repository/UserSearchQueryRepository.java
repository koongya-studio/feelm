package com.feelm.backend.domain.user.repository;

import com.feelm.backend.domain.user.entity.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 사용자 검색(API-019)의 닉네임 접두 조회다. 읽기 전용이며 행을 바꾸지 않는다.
 *
 * <p>Native Query를 쓰는 이유는 인덱스와 <b>문자 그대로 같은 식</b>을 써야 하기 때문이다(컨벤션 9장). 조건·정렬·커서가 모두 닉네임 유일 인덱스와 같은
 * 정규화식 {@code lower(replace(nickname, ' ', ''))}를 써야 검색 판정이 닉네임 중복 판정과 갈리지 않고 인덱스도 탄다. JPQL로도 같은
 * 함수를 조합할 수는 있으나 방언이 만들어 내는 SQL이 인덱스 식과 일치한다는 보장이 없고, 커서에 쓸 정렬 키를 계산 컬럼으로 함께 돌려받아야 해서 Entity 매핑이 맞지
 * 않는다.
 *
 * <p>정렬·커서 키는 {@code (정규화 닉네임, id)}다. 정규화 닉네임만으로는 동률을 깰 수 없어 페이지 경계에서 행이 중복되거나 빠진다. 커서 비교와 {@code
 * ORDER BY}가 같은 식을 쓰므로 콜레이션이 달라 순서가 어긋날 여지는 없다.
 *
 * <p>제외 대상(본인·양방향 차단 상대)은 조회한 뒤 걸러내지 않고 질의에서 건다. 뒤에서 걸러내면 요청한 size보다 짧은 페이지가 나오고 {@code hasNext}
 * 판정이 틀어진다. 제외 id는 {@code in} 절에 그대로 들어가므로 호출자가 건수를 제한한다(권장 상한 1,000건, 컨벤션 11장).
 */
@Repository
public class UserSearchQueryRepository {

    /** 정규화 닉네임. 조회 조건·정렬·커서가 모두 이 식을 쓴다. */
    private static final String NORMALIZED_NICKNAME = "lower(replace(u.nickname, ' ', ''))";

    /**
     * {@code LIKE} 패턴의 이스케이프 문자. {@code _}는 닉네임에 실제로 쓰이는 문자라({@code NicknamePolicy}) 한 글자 와일드카드로
     * 해석되면 안 되고, {@code %}와 이스케이프 문자 자신도 같은 이유로 리터럴이어야 한다.
     */
    public static final char LIKE_ESCAPE = '\\';

    private static final String SELECT_COLUMNS =
            "SELECT u.id, u.nickname, u.visibility, " + NORMALIZED_NICKNAME + " AS sort_key";

    private static final String FROM_AND_FILTER =
            " FROM users u"
                    + " WHERE u.status = 'ACTIVE'"
                    + " AND u.id NOT IN (:excludedUserIds)"
                    + " AND "
                    + NORMALIZED_NICKNAME
                    + " LIKE :prefix ESCAPE '\\'";

    private static final String ORDER_BY = " ORDER BY " + NORMALIZED_NICKNAME + ", u.id";

    private static final String FIRST_PAGE_QUERY = SELECT_COLUMNS + FROM_AND_FILTER + ORDER_BY;

    private static final String NEXT_PAGE_QUERY =
            SELECT_COLUMNS
                    + FROM_AND_FILTER
                    + " AND ("
                    + NORMALIZED_NICKNAME
                    + " > :afterNickname"
                    + " OR ("
                    + NORMALIZED_NICKNAME
                    + " = :afterNickname AND u.id > :afterUserId))"
                    + ORDER_BY;

    private final EntityManager entityManager;

    public UserSearchQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * 닉네임 접두가 일치하는 활성 사용자의 첫 페이지.
     *
     * @param normalizedPrefix 이미 정규화·이스케이프된 검색어. {@code %}는 이 메서드가 붙인다
     * @param excludedUserIds 제외할 사용자 id. 비어 있으면 {@code in ()}이 되어 SQL이 깨지므로 호출자가 최소 한 건(본인)을 넣는다
     */
    public List<SearchedUser> findFirstPage(
            String normalizedPrefix, Collection<Long> excludedUserIds, int limit) {
        Query query = entityManager.createNativeQuery(FIRST_PAGE_QUERY);
        bindFilter(query, normalizedPrefix, excludedUserIds, limit);
        return toSearchedUsers(query);
    }

    /**
     * 커서가 가리키는 {@code (정규화 닉네임, id)} 다음에 오는 페이지.
     *
     * <p>커서 이후 조건은 정렬과 같은 식으로 쓰므로, 첫 페이지 이후에 차단이 생기거나 계정이 비활성으로 바뀌어도 커서 위치는 유지된다. 그 행만 결과에서 빠진다.
     */
    public List<SearchedUser> findPageAfter(
            String normalizedPrefix,
            Collection<Long> excludedUserIds,
            String afterNickname,
            long afterUserId,
            int limit) {
        Query query = entityManager.createNativeQuery(NEXT_PAGE_QUERY);
        bindFilter(query, normalizedPrefix, excludedUserIds, limit);
        query.setParameter("afterNickname", afterNickname);
        query.setParameter("afterUserId", afterUserId);
        return toSearchedUsers(query);
    }

    private static void bindFilter(
            Query query, String normalizedPrefix, Collection<Long> excludedUserIds, int limit) {
        query.setParameter("prefix", normalizedPrefix + "%");
        query.setParameter("excludedUserIds", excludedUserIds);
        query.setMaxResults(limit);
    }

    private static List<SearchedUser> toSearchedUsers(Query query) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(
                        row ->
                                new SearchedUser(
                                        ((Number) row[0]).longValue(),
                                        (String) row[1],
                                        Visibility.valueOf((String) row[2]),
                                        (String) row[3]))
                .toList();
    }

    /**
     * 검색 결과 한 행.
     *
     * @param sortKey DB가 계산한 정규화 닉네임. 커서를 만들 때 애플리케이션에서 다시 정규화하지 않고 이 값을 그대로 쓴다. 같은 식이 만든 값이어야 다음
     *     페이지 질의의 비교가 정렬과 어긋나지 않는다
     */
    public record SearchedUser(
            long userId, String nickname, Visibility visibility, String sortKey) {}
}
