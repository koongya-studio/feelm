package com.feelm.backend.domain.taste.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 영화 군집과 화면 표시 정보를 연결하는 팝콘 맛 기준정보다. */
@Entity
@Table(name = "popcorn_flavors")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PopcornFlavor {

    @Id private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "display_name", nullable = false, length = 30)
    private String displayName;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 원시 군집 번호는 영화 분류에만 사용하며 API 응답에는 노출하지 않는다. */
    @Column(name = "cluster_id", nullable = false, unique = true)
    private int clusterId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "representative_keywords", nullable = false)
    private List<String> representativeKeywords;
}
