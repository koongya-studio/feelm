package com.feelm.backend.domain.ott.repository;

import com.feelm.backend.domain.ott.entity.UserOtt;
import com.feelm.backend.domain.ott.entity.UserOttId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserOttRepository extends JpaRepository<UserOtt, UserOttId> {

    /** 구독 중인 OTT 식별자를 오름차순으로 읽는다(API-026). 엔티티에 다른 필드가 없어 식별자만 투영한다. */
    @Query("select o.ottId from UserOtt o where o.userId = :userId order by o.ottId asc")
    List<Long> findOttIdsByUserId(@Param("userId") long userId);

    /**
     * 사용자의 구독 행을 모두 지운다(API-027의 전체 교체 전반부).
     *
     * <p>벌크 삭제는 영속성 컨텍스트를 flush·clear 하므로 호출 이후에는 앞서 읽은 엔티티가 detach 된다. 계정 행 잠금은 DB가 트랜잭션 끝까지 들고
     * 있으므로 detach 되어도 직렬화는 유지된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserOtt o where o.userId = :userId")
    void deleteByUserId(@Param("userId") long userId);
}
