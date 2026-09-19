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
        List<Object> keywords,
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

    public record Share(String label, int share) {}

    public record Comparison(String label, int delta, boolean isNew) {}

    public record Generation(
            int contentVersion, Instant snapshotAt, Instant cutoffAt, boolean finalSnapshot) {}
}
