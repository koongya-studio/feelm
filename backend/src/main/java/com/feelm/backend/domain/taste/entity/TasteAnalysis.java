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

    private TasteAnalysis(
            Long userId,
            Analysis analysis,
            List<Keyword> keywords,
            int ratingCount,
            Instant analyzedAt) {
        this.userId = userId;
        this.analysis = analysis;
        this.keywords = keywords;
        this.ratingCount = ratingCount;
        this.analyzedAt = analyzedAt;
    }

    /** 평가 변경 트랜잭션에서 새 캐시 행을 만든다(S15P21E106-775). */
    public static TasteAnalysis of(
            long userId,
            Analysis analysis,
            List<Keyword> keywords,
            int ratingCount,
            Instant analyzedAt) {
        return new TasteAnalysis(
                userId, requireAnalysis(analysis), copyKeywords(keywords), ratingCount, analyzedAt);
    }

    /**
     * 이미 있는 캐시 행을 새 집계로 덮어쓴다. 사용자당 한 행이라 {@code user_id}는 바뀌지 않는다.
     *
     * <p>{@code keywords}가 비면 조회가 {@code insufficientData: true}로 나간다({@code
     * TasteAnalysisResponse.from}). 평가는 충분하지만 규칙을 못 넘긴 경우도 같은 값이라는 뜻이며, 계약이 그 조합을 명시한다.
     */
    public void refresh(
            Analysis analysis, List<Keyword> keywords, int ratingCount, Instant analyzedAt) {
        this.analysis = requireAnalysis(analysis);
        this.keywords = copyKeywords(keywords);
        this.ratingCount = ratingCount;
        this.analyzedAt = analyzedAt;
    }

    private static Analysis requireAnalysis(Analysis analysis) {
        return analysis == null ? new Analysis(null, null, null, null) : analysis;
    }

    private static List<Keyword> copyKeywords(List<Keyword> keywords) {
        return keywords == null ? List.of() : List.copyOf(keywords);
    }

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
