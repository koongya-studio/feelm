package com.feelm.backend.domain.ott.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스가 지원하는 OTT 기준정보. {@code otts} 테이블에 매핑한다.
 *
 * <p><b>읽기 전용이다.</b> 행의 생성과 갱신은 콘텐츠 적재 경로({@code backend/scripts/run-tmdb-ingestion.sh})가 승인된 매핑
 * CSV를 기준으로 수행하며, 백엔드 코드는 조회만 한다. 매핑 목록은 API-025에서 넷플릭스·디즈니+·왓챠·웨이브·티빙 다섯 개로 확정했다.
 */
@Entity
@Table(name = "otts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ott {

    @Id private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "logo_url")
    private String logoUrl;

    /** TMDb watch provider 식별자. 적재 경로가 UPSERT 기준으로 쓰며 API 응답에는 노출하지 않는다. */
    @Column(name = "tmdb_provider_id", nullable = false, unique = true)
    private int tmdbProviderId;
}
