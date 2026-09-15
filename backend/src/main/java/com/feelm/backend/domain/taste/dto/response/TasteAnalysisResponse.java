package com.feelm.backend.domain.taste.dto.response;

import com.feelm.backend.domain.taste.entity.TasteAnalysis;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TasteAnalysisResponse(
        int ratingCount,
        boolean insufficientData,
        Analysis analysis,
        List<Keyword> keywords,
        Instant analyzedAt) {

    public static TasteAnalysisResponse empty() {
        return new TasteAnalysisResponse(0, true, Analysis.empty(), List.of(), null);
    }

    public static TasteAnalysisResponse from(TasteAnalysis tasteAnalysis) {
        TasteAnalysis.Analysis cached = tasteAnalysis.getAnalysis();
        Analysis analysis =
                new Analysis(
                        cached.genres().stream().map(Genre::from).toList(),
                        cached.countries().stream().map(Country::from).toList(),
                        cached.eras().stream().map(Era::from).toList(),
                        cached.directors().stream().map(Director::from).toList());
        List<Keyword> keywords = tasteAnalysis.getKeywords().stream().map(Keyword::from).toList();
        return new TasteAnalysisResponse(
                tasteAnalysis.getRatingCount(),
                keywords.isEmpty(),
                analysis,
                keywords,
                tasteAnalysis.getAnalyzedAt());
    }

    public record Analysis(
            List<Genre> genres, List<Country> countries, List<Era> eras, List<Director> directors) {

        private static Analysis empty() {
            return new Analysis(List.of(), List.of(), List.of(), List.of());
        }
    }

    public record Genre(
            String code, String name, int count, BigDecimal averageScore, BigDecimal share) {

        private static Genre from(TasteAnalysis.Genre genre) {
            return new Genre(
                    genre.code(), genre.name(), genre.count(), genre.averageScore(), genre.share());
        }
    }

    public record Country(
            String code, String name, int count, BigDecimal averageScore, BigDecimal share) {

        private static Country from(TasteAnalysis.Country country) {
            return new Country(
                    country.code(),
                    country.name(),
                    country.count(),
                    country.averageScore(),
                    country.share());
        }
    }

    public record Era(String label, int count, BigDecimal averageScore, BigDecimal share) {

        private static Era from(TasteAnalysis.Era era) {
            return new Era(era.label(), era.count(), era.averageScore(), era.share());
        }
    }

    public record Director(long personId, String name, int count, BigDecimal averageScore) {

        private static Director from(TasteAnalysis.Director director) {
            return new Director(
                    director.personId(),
                    director.name(),
                    director.count(),
                    director.averageScore());
        }
    }

    public record Keyword(String code, String label) {

        private static Keyword from(TasteAnalysis.Keyword keyword) {
            return new Keyword(keyword.code(), keyword.label());
        }
    }
}
