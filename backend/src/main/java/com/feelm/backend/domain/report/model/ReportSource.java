package com.feelm.backend.domain.report.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ReportSource {

    private ReportSource() {}

    public record Snapshot(
            List<Frame> frames, List<Genre> genres, List<Country> countries, List<Person> people) {}

    public record Frame(
            long frameId,
            long userId,
            long movieId,
            BigDecimal score,
            Instant createdAt,
            Integer releaseYear,
            String flavorCode,
            String flavorName,
            String posterUrl,
            String backdropUrl) {}

    public record Genre(long frameId, String code, String name) {}

    public record Country(long frameId, String code) {}

    public record Person(
            long frameId,
            long personId,
            String name,
            String originalName,
            String profileUrl,
            Role role) {}

    public enum Role {
        DIRECTOR,
        ACTOR
    }
}
