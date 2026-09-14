package com.feelm.backend.domain.ott.repository;

import com.feelm.backend.domain.ott.entity.Ott;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OttRepository extends JpaRepository<Ott, Long> {

    /**
     * 기준정보 전체를 식별자 오름차순으로 읽는다(API-025).
     *
     * <p>기존 OTT 식별자는 보존하고 신규 OTT만 DB 시퀀스로 식별자를 부여하므로 CSV 행 순서는 식별자를 결정하지 않는다. {@code otts}에 별도 정렬
     * 열이 없어 API 응답을 안정적으로 유지하기 위한 조회 기준으로 식별자 오름차순을 사용한다.
     */
    List<Ott> findAllByOrderByIdAsc();

    /** 요청한 식별자가 모두 존재하는지 한 번의 조회로 판정한다(API-027). 호출자는 중복을 먼저 제거한다. */
    long countByIdIn(Collection<Long> ids);
}
