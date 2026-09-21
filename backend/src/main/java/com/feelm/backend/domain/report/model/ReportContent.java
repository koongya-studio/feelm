package com.feelm.backend.domain.report.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReportContent(
        Summary summary,
        List<GenreRank> topGenres,
        List<PersonRank> topActors,
        List<PersonRank> topDirectors,
        Object characterCard,
        List<FlavorChange> flavorChanges,
        FourCut fourCut,
        List<Share> genreShares,
        List<Share> countryShares,
        List<Share> eraShares,
        List<Keyword> keywords,
        List<Comparison> comparisons,
        List<String> highlights,
        Generation generation) {

    public record Summary(int ratingCount, int newFlavorCount, BigDecimal averageScore) {}

    public record GenreRank(String code, String name, int count) {}

    public record PersonRank(long personId, String name, int count) {}

    public record FlavorChange(String flavorCode, int before, int after) {}

    public record FourCut(
            GenreCard genre, DirectorCard director, PersonCard actor, Object character) {}

    public record ImageRef(String url) {}

    public record GenreCard(String name, String displayName, ImageRef image) {}

    public record DirectorCard(String name, String displayName, ImageRef image, Object work) {}

    public record PersonCard(String name, String displayName, ImageRef image) {}

    /**
     * 3.4 · 7.4 `취향 키워드` 한 개. `rationale`은 그 반기 집계에서 나온 수치를 그대로 적은 근거 문장이다. 성격 · 전개 태그는 기준정보가 없어 쓰지
     * 않는다(업무 -774).
     */
    public record Keyword(String code, String label, String rationale) {}

    public record Share(String label, int share) {}

    public record Comparison(String label, int delta, boolean isNew) {}

    public record Generation(
            int contentVersion, Instant snapshotAt, Instant cutoffAt, boolean finalSnapshot) {}
}
