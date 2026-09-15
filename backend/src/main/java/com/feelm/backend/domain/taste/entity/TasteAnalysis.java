package com.feelm.backend.domain.taste.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 평가 변경 시 계산된 사용자별 취향 분석 캐시다. */
@Entity
@Table(name = "taste_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TasteAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Analysis analysis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Keyword> keywords = List.of();

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Analysis(
            List<Genre> genres, List<Country> countries, List<Era> eras, List<Director> directors) {

        public Analysis {
            genres = genres == null ? List.of() : List.copyOf(genres);
            countries = countries == null ? List.of() : List.copyOf(countries);
            eras = eras == null ? List.of() : List.copyOf(eras);
            directors = directors == null ? List.of() : List.copyOf(directors);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Genre(
            String code, String name, int count, BigDecimal averageScore, BigDecimal share) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Country(
            String code, String name, int count, BigDecimal averageScore, BigDecimal share) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Era(String label, int count, BigDecimal averageScore, BigDecimal share) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Director(long personId, String name, int count, BigDecimal averageScore) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keyword(String code, String label) {}
}
