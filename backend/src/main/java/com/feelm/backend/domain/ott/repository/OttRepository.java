package com.feelm.backend.domain.ott.repository;

import com.feelm.backend.domain.ott.entity.Ott;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OttRepository extends JpaRepository<Ott, Long> {

    /**
     * 기준정보 전체를 식별자 오름차순으로 읽는다(API-025).
     *
     * <p>{@code otts}에 정렬 열이 없고 식별자가 적재 CSV의 순서를 그대로 따르므로, 재조회할 때마다 화면의 OTT 배지 순서가 흔들리지 않도록 식별자를 정렬
     * 기준으로 고정한다.
     */
    List<Ott> findAllByOrderByIdAsc();

    /** 요청한 식별자가 모두 존재하는지 한 번의 조회로 판정한다(API-027). 호출자는 중복을 먼저 제거한다. */
    long countByIdIn(Collection<Long> ids);
}
