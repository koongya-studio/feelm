package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.model.ReportContent.Comparison;
import com.feelm.backend.domain.report.model.ReportContent.DirectorCard;
import com.feelm.backend.domain.report.model.ReportContent.FlavorChange;
import com.feelm.backend.domain.report.model.ReportContent.FourCut;
import com.feelm.backend.domain.report.model.ReportContent.Generation;
import com.feelm.backend.domain.report.model.ReportContent.GenreCard;
import com.feelm.backend.domain.report.model.ReportContent.GenreRank;
import com.feelm.backend.domain.report.model.ReportContent.ImageRef;
import com.feelm.backend.domain.report.model.ReportContent.PersonCard;
import com.feelm.backend.domain.report.model.ReportContent.PersonRank;
import com.feelm.backend.domain.report.model.ReportContent.Share;
import com.feelm.backend.domain.report.model.ReportContent.Summary;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.model.ReportSource;
import com.feelm.backend.domain.report.model.ReportSource.Role;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReportContentAssembler {

    private static final int DISPLAY_LIMIT = 3;

    public ReportContent assemble(
            ReportPeriod period,
            Instant effectiveEnd,
            Instant snapshotAcquiredAt,
            boolean finalSnapshot,
            List<ReportSource.Frame> frames,
            List<ReportSource.Genre> genres,
            List<ReportSource.Country> countries,
            List<ReportSource.Person> people,
            boolean previousReportExists) {
        Set<Long> currentIds = frameIds(frames, period.startInclusive(), effectiveEnd);
        Set<Long> previousIds =
                frameIds(frames, period.previous().startInclusive(), period.startInclusive());
        Set<Long> olderIds =
                frames.stream()
                        .filter(frame -> frame.createdAt().isBefore(period.startInclusive()))
                        .map(ReportSource.Frame::frameId)
                        .collect(Collectors.toSet());
        Map<Long, ReportSource.Frame> frameById =
                frames.stream()
                        .collect(
                                Collectors.toMap(ReportSource.Frame::frameId, Function.identity()));

        List<GenreStat> currentGenres = genreStats(genres, currentIds, frameById);
        List<GenreStat> previousGenres = genreStats(genres, previousIds, frameById);
        Set<String> historicalGenreCodes =
                genres.stream()
                        .filter(genre -> olderIds.contains(genre.frameId()))
                        .map(ReportSource.Genre::code)
                        .collect(Collectors.toSet());
        List<PersonStat> currentDirectors = personStats(people, currentIds, Role.DIRECTOR);
        List<PersonStat> currentActors = personStats(people, currentIds, Role.ACTOR);
        Set<Long> historicalDirectorIds =
                people.stream()
                        .filter(person -> person.role() == Role.DIRECTOR)
                        .filter(person -> olderIds.contains(person.frameId()))
                        .map(ReportSource.Person::personId)
                        .collect(Collectors.toSet());

        List<ReportSource.Frame> currentFrames = selectFrames(frames, currentIds);
        List<ReportSource.Frame> previousFrames = selectFrames(frames, previousIds);
        int currentTotal = currentFrames.size();
        int previousTotal = previousFrames.size();

        List<GenreRank> topGenres =
                currentGenres.stream()
                        .limit(DISPLAY_LIMIT)
                        .map(stat -> new GenreRank(stat.code(), stat.name(), stat.count()))
                        .toList();
        List<PersonRank> topDirectors = personRanks(currentDirectors);
        List<PersonRank> topActors = personRanks(currentActors);
        List<Share> genreShares =
                currentGenres.stream()
                        .limit(DISPLAY_LIMIT)
                        .map(stat -> new Share(stat.name(), percentage(stat.count(), currentTotal)))
                        .toList();
        List<Share> countryShares = countryShares(countries, currentIds, currentTotal);
        List<Share> eraShares = eraShares(currentFrames, currentTotal);
        List<Comparison> comparisons =
                previousReportExists
                        ? comparisons(
                                currentGenres,
                                previousGenres,
                                historicalGenreCodes,
                                currentTotal,
                                previousTotal)
                        : List.of();
        List<FlavorChange> flavorChanges = flavorChanges(currentFrames, previousFrames);
        Set<String> historicalFlavors =
                frames.stream()
                        .filter(frame -> olderIds.contains(frame.frameId()))
                        .map(ReportSource.Frame::flavorCode)
                        .filter(code -> code != null)
                        .collect(Collectors.toSet());
        Map<String, String> newFlavors =
                currentFrames.stream()
                        .filter(frame -> frame.flavorCode() != null)
                        .filter(frame -> !historicalFlavors.contains(frame.flavorCode()))
                        .collect(
                                Collectors.toMap(
                                        ReportSource.Frame::flavorCode,
                                        frame -> flavorName(frame.flavorName(), frame.flavorCode()),
                                        (left, right) -> left,
                                        LinkedHashMap::new));
        int newDirectorCount =
                (int)
                        currentDirectors.stream()
                                .map(PersonStat::personId)
                                .filter(personId -> !historicalDirectorIds.contains(personId))
                                .count();

        Summary summary = new Summary(currentTotal, newFlavors.size(), averageScore(currentFrames));
        FourCut fourCut = fourCut(currentGenres, currentDirectors, currentActors);
        List<String> highlights =
                highlights(comparisons, newDirectorCount, new ArrayList<>(newFlavors.values()));

        return new ReportContent(
                summary,
                topGenres,
                topActors,
                topDirectors,
                null,
                flavorChanges,
                fourCut,
                genreShares,
                countryShares,
                eraShares,
                List.of(),
                comparisons,
                highlights,
                new Generation(1, snapshotAcquiredAt, period.cutoffExclusive(), finalSnapshot));
    }

    private static Set<Long> frameIds(List<ReportSource.Frame> frames, Instant start, Instant end) {
        return frames.stream()
                .filter(
                        frame ->
                                !frame.createdAt().isBefore(start)
                                        && frame.createdAt().isBefore(end))
                .map(ReportSource.Frame::frameId)
                .collect(Collectors.toSet());
    }

    private static List<ReportSource.Frame> selectFrames(
            List<ReportSource.Frame> frames, Set<Long> ids) {
        return frames.stream().filter(frame -> ids.contains(frame.frameId())).toList();
    }

    private static List<GenreStat> genreStats(
            List<ReportSource.Genre> genres,
            Set<Long> frameIds,
            Map<Long, ReportSource.Frame> frameById) {
        Map<String, MutableGenreStat> stats = new HashMap<>();
        for (ReportSource.Genre genre : genres) {
            if (!frameIds.contains(genre.frameId())) {
                continue;
            }
            ReportSource.Frame frame = frameById.get(genre.frameId());
            MutableGenreStat stat =
                    stats.computeIfAbsent(
                            genre.code(),
                            ignored -> new MutableGenreStat(genre.code(), genre.name()));
            stat.add(frame.score());
        }
        return stats.values().stream()
                .map(MutableGenreStat::freeze)
                .sorted(
                        Comparator.comparingInt(GenreStat::count)
                                .reversed()
                                .thenComparing(GenreStat::averageScore, Comparator.reverseOrder())
                                .thenComparing(GenreStat::code))
                .toList();
    }

    private static List<PersonStat> personStats(
            List<ReportSource.Person> people, Set<Long> frameIds, Role role) {
        Map<Long, MutablePersonStat> stats = new HashMap<>();
        for (ReportSource.Person person : people) {
            if (person.role() != role || !frameIds.contains(person.frameId())) {
                continue;
            }
            stats.computeIfAbsent(
                            person.personId(),
                            ignored ->
                                    new MutablePersonStat(
                                            person.personId(), person.name(), person.profileUrl()))
                    .add(person.frameId());
        }
        return stats.values().stream()
                .map(MutablePersonStat::freeze)
                .sorted(
                        Comparator.comparingInt(PersonStat::count)
                                .reversed()
                                .thenComparingLong(PersonStat::personId))
                .toList();
    }

    private static List<PersonRank> personRanks(List<PersonStat> stats) {
        return stats.stream()
                .limit(DISPLAY_LIMIT)
                .map(stat -> new PersonRank(stat.personId(), stat.name(), stat.count()))
                .toList();
    }

    private static List<Share> countryShares(
            List<ReportSource.Country> countries, Set<Long> currentIds, int total) {
        Map<String, Set<Long>> counts = new HashMap<>();
        for (ReportSource.Country country : countries) {
            if (currentIds.contains(country.frameId())) {
                counts.computeIfAbsent(country.code(), ignored -> new HashSet<>())
                        .add(country.frameId());
            }
        }
        return counts.entrySet().stream()
                .sorted(
                        Comparator.<Map.Entry<String, Set<Long>>>comparingInt(
                                        entry -> entry.getValue().size())
                                .reversed()
                                .thenComparing(Map.Entry::getKey))
                .limit(DISPLAY_LIMIT)
                .map(
                        entry ->
                                new Share(
                                        countryName(entry.getKey()),
                                        percentage(entry.getValue().size(), total)))
                .toList();
    }

    private static List<Share> eraShares(List<ReportSource.Frame> frames, int total) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ReportSource.Frame frame : frames) {
            if (frame.releaseYear() != null) {
                int decade = Math.floorDiv(frame.releaseYear(), 10) * 10;
                counts.merge(decade, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(
                        Map.Entry.<Integer, Integer>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .limit(DISPLAY_LIMIT)
                .map(entry -> new Share(entry.getKey() + "년대", percentage(entry.getValue(), total)))
                .toList();
    }

    private static List<Comparison> comparisons(
            List<GenreStat> current,
            List<GenreStat> previous,
            Set<String> historicalCodes,
            int currentTotal,
            int previousTotal) {
        Map<String, GenreStat> currentByCode = byGenreCode(current);
        Map<String, GenreStat> previousByCode = byGenreCode(previous);
        Set<String> codes = new HashSet<>(currentByCode.keySet());
        codes.addAll(previousByCode.keySet());
        return codes.stream()
                .map(
                        code -> {
                            GenreStat currentStat = currentByCode.get(code);
                            GenreStat previousStat = previousByCode.get(code);
                            int currentCount = currentStat == null ? 0 : currentStat.count();
                            int previousCount = previousStat == null ? 0 : previousStat.count();
                            double delta =
                                    ratio(currentCount, currentTotal)
                                            - ratio(previousCount, previousTotal);
                            String label =
                                    currentStat != null ? currentStat.name() : previousStat.name();
                            return new ComparisonCandidate(
                                    code,
                                    label,
                                    delta,
                                    currentCount > 0 && !historicalCodes.contains(code));
                        })
                .filter(candidate -> Math.round(candidate.delta()) != 0)
                .sorted(
                        Comparator.comparingDouble(
                                        (ComparisonCandidate candidate) ->
                                                Math.abs(candidate.delta()))
                                .reversed()
                                .thenComparing(ComparisonCandidate::code))
                .limit(DISPLAY_LIMIT)
                .map(
                        candidate ->
                                new Comparison(
                                        candidate.label(),
                                        Math.toIntExact(Math.round(candidate.delta())),
                                        candidate.isNew()))
                .toList();
    }

    private static Map<String, GenreStat> byGenreCode(List<GenreStat> stats) {
        return stats.stream().collect(Collectors.toMap(GenreStat::code, Function.identity()));
    }

    private static List<FlavorChange> flavorChanges(
            List<ReportSource.Frame> current, List<ReportSource.Frame> previous) {
        Map<String, Integer> currentCounts = flavorCounts(current);
        Map<String, Integer> previousCounts = flavorCounts(previous);
        Set<String> codes = new HashSet<>(currentCounts.keySet());
        codes.addAll(previousCounts.keySet());
        return codes.stream()
                .sorted()
                .map(
                        code ->
                                new FlavorChange(
                                        code,
                                        previousCounts.getOrDefault(code, 0),
                                        currentCounts.getOrDefault(code, 0)))
                .toList();
    }

    private static Map<String, Integer> flavorCounts(List<ReportSource.Frame> frames) {
        return frames.stream()
                .map(ReportSource.Frame::flavorCode)
                .filter(code -> code != null)
                .collect(Collectors.toMap(Function.identity(), ignored -> 1, Integer::sum));
    }

    private static FourCut fourCut(
            List<GenreStat> genres, List<PersonStat> directors, List<PersonStat> actors) {
        GenreCard genre =
                genres.isEmpty()
                        ? null
                        : new GenreCard(genres.getFirst().name(), null, new ImageRef(null));
        DirectorCard director =
                directors.isEmpty()
                        ? null
                        : new DirectorCard(
                                directors.getFirst().name(),
                                null,
                                new ImageRef(directors.getFirst().profileUrl()),
                                null);
        PersonCard actor =
                actors.isEmpty()
                        ? null
                        : new PersonCard(
                                actors.getFirst().name(),
                                null,
                                new ImageRef(actors.getFirst().profileUrl()));
        return new FourCut(genre, director, actor, null);
    }

    private static List<String> highlights(
            List<Comparison> comparisons, int newDirectorCount, List<String> newFlavors) {
        List<String> highlights = new ArrayList<>();
        comparisons.stream()
                .filter(comparison -> comparison.delta() > 0)
                .findFirst()
                .ifPresent(
                        comparison ->
                                highlights.add(
                                        "지난 반기보다 "
                                                + comparison.label()
                                                + " 취향이 "
                                                + comparison.delta()
                                                + "%p 증가했어요"));
        if (newDirectorCount > 0) {
            highlights.add("새롭게 발견한 감독 " + newDirectorCount + "명");
        }
        if (!newFlavors.isEmpty()) {
            highlights.add("새로 넓어진 팝콘 맛: " + String.join("·", newFlavors));
        }
        if (highlights.isEmpty()) {
            highlights.add("이번 반기의 영화 취향을 한 장에 담았어요");
        }
        return highlights.stream().limit(3).toList();
    }

    private static BigDecimal averageScore(List<ReportSource.Frame> frames) {
        if (frames.isEmpty()) {
            return BigDecimal.ZERO.setScale(1);
        }
        BigDecimal sum =
                frames.stream()
                        .map(ReportSource.Frame::score)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(frames.size()), 1, RoundingMode.HALF_UP);
    }

    private static int percentage(int count, int total) {
        return Math.toIntExact(Math.round(ratio(count, total)));
    }

    private static double ratio(int count, int total) {
        return total == 0 ? 0 : count * 100.0 / total;
    }

    private static String countryName(String code) {
        if ("KR".equals(code)) {
            return "한국";
        }
        Locale locale = new Locale.Builder().setRegion(code).build();
        String name = locale.getDisplayCountry(Locale.KOREAN);
        return name.isBlank() ? code : name;
    }

    private static String flavorName(String name, String code) {
        return name == null || name.isBlank() ? code : name;
    }

    private record GenreStat(String code, String name, int count, BigDecimal averageScore) {}

    private static final class MutableGenreStat {
        private final String code;
        private final String name;
        private int count;
        private BigDecimal scoreSum = BigDecimal.ZERO;

        private MutableGenreStat(String code, String name) {
            this.code = code;
            this.name = name;
        }

        private void add(BigDecimal score) {
            count++;
            scoreSum = scoreSum.add(score);
        }

        private GenreStat freeze() {
            return new GenreStat(
                    code,
                    name,
                    count,
                    scoreSum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP));
        }
    }

    private record PersonStat(long personId, String name, String profileUrl, int count) {}

    private static final class MutablePersonStat {
        private final long personId;
        private final String name;
        private final String profileUrl;
        private final Set<Long> frameIds = new HashSet<>();

        private MutablePersonStat(long personId, String name, String profileUrl) {
            this.personId = personId;
            this.name = name;
            this.profileUrl = profileUrl;
        }

        private void add(long frameId) {
            frameIds.add(frameId);
        }

        private PersonStat freeze() {
            return new PersonStat(personId, name, profileUrl, frameIds.size());
        }
    }

    private record ComparisonCandidate(String code, String label, double delta, boolean isNew) {}
}
