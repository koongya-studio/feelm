package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.CharacterAxisMedians;
import com.feelm.backend.domain.report.model.CharacterAxisMetrics;
import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.model.ReportContent.CharacterCardContent;
import com.feelm.backend.domain.report.model.ReportContent.CharacterCutCard;
import com.feelm.backend.domain.report.model.ReportContent.Comparison;
import com.feelm.backend.domain.report.model.ReportContent.DirectorCard;
import com.feelm.backend.domain.report.model.ReportContent.FlavorChange;
import com.feelm.backend.domain.report.model.ReportContent.FourCut;
import com.feelm.backend.domain.report.model.ReportContent.Generation;
import com.feelm.backend.domain.report.model.ReportContent.GenreCard;
import com.feelm.backend.domain.report.model.ReportContent.GenreRank;
import com.feelm.backend.domain.report.model.ReportContent.ImageRef;
import com.feelm.backend.domain.report.model.ReportContent.Keyword;
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
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReportContentAssembler {

    private static final int DISPLAY_LIMIT = 3;

    /** 시대 칸에서 연대 이름을 그대로 쓰는 줄 수. 나머지는 `그 이전` 한 줄로 합친다 */
    private static final int ERA_NAMED_LIMIT = 2;

    private static final String ERA_REST_LABEL = "그 이전";

    /** 취향 키워드 칸 수. 와이어프레임이 네 개를 그린다 */
    private static final int KEYWORD_LIMIT = 4;

    /** 한 사람을 `몰아봤다`고 부를 최소 편수 */
    private static final int PERSON_BINGE = 3;

    private static final int NEW_DIRECTOR_MIN = 3;

    /** 한 항목이 그 반기를 대표한다고 볼 최소 비율 */
    private static final int DEVOTEE_SHARE = 50;

    private static final int OMNIVORE_GENRES = 8;
    private static final int CLASSIC_YEARS = 20;
    private static final int CLASSIC_SHARE = 30;
    private static final int RECENT_SHARE = 50;
    private static final int ERA_LOVER_SHARE = 40;
    private static final int COUNTRY_SHARE = 50;
    private static final int FAVORITE_MIN_COUNT = 2;
    private static final BigDecimal FAVORITE_MIN_SCORE = new BigDecimal("4.0");
    private static final BigDecimal GENEROUS_SCORE = new BigDecimal("4.3");
    private static final BigDecimal STRICT_SCORE = new BigDecimal("3.0");
    private static final Map<String, String> TMDB_GENRE_DISPLAY_NAMES =
            Map.ofEntries(
                    Map.entry("28", "ACTION"),
                    Map.entry("12", "ADVENTURE"),
                    Map.entry("16", "ANIMATION"),
                    Map.entry("35", "COMEDY"),
                    Map.entry("80", "CRIME"),
                    Map.entry("99", "DOCUMENTARY"),
                    Map.entry("18", "DRAMA"),
                    Map.entry("10751", "FAMILY"),
                    Map.entry("14", "FANTASY"),
                    Map.entry("36", "HISTORY"),
                    Map.entry("27", "HORROR"),
                    Map.entry("10402", "MUSIC"),
                    Map.entry("9648", "MYSTERY"),
                    Map.entry("10749", "ROMANCE"),
                    Map.entry("878", "SCIENCE FICTION"),
                    Map.entry("10770", "TV MOVIE"),
                    Map.entry("53", "THRILLER"),
                    Map.entry("10752", "WAR"),
                    Map.entry("37", "WESTERN"));

    public ReportContent assemble(
            ReportPeriod period,
            Instant effectiveEnd,
            Instant snapshotAcquiredAt,
            boolean finalSnapshot,
            List<ReportSource.Frame> frames,
            List<ReportSource.Genre> genres,
            List<ReportSource.Country> countries,
            List<ReportSource.Person> people,
            boolean previousReportExists,
            CharacterAxisMedians characterAxisMedians) {
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
        List<FlavorChange> flavorChanges = flavorChanges(currentFrames, previousFrames);
        Set<String> historicalFlavors =
                frames.stream()
                        .filter(frame -> olderIds.contains(frame.frameId()))
                        .map(ReportSource.Frame::flavorCode)
                        .filter(code -> code != null)
                        .collect(Collectors.toSet());
        List<Comparison> comparisons =
                previousReportExists
                        ? comparisons(
                                currentFrames,
                                previousFrames,
                                historicalFlavors,
                                currentTotal,
                                previousTotal)
                        : List.of();
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

        /*
         * 캐릭터 카드. 판정은 그 반기 대상자 전체의 중앙값 스냅샷 기준 상대 판정이라 조립기가 아니라
         * `CharacterCardSelector`가 한다(업무 -779). 프레임이 3편 이상이면 리포트가 생기고 카드도
         * 늘 함께 생긴다 — 16종이 모든 조합을 덮어 후보 없음이 없다(API-060).
         */
        CharacterAxisMetrics axisMetrics =
                CharacterCardSelector.metrics(frames, period.startInclusive(), effectiveEnd);
        CharacterCardContent characterCard =
                CharacterCardSelector.select(axisMetrics, characterAxisMedians, currentTotal);
        FourCut fourCut =
                fourCut(currentGenres, currentDirectors, currentActors, characterCard.name());
        List<String> highlights =
                highlights(comparisons, newDirectorCount, new ArrayList<>(newFlavors.values()));
        List<Keyword> keywords =
                keywords(
                        period,
                        currentTotal,
                        currentFrames,
                        currentGenres,
                        currentDirectors,
                        currentActors,
                        newFlavors,
                        newDirectorCount,
                        eraShares,
                        countryShares,
                        summary.averageScore());

        return new ReportContent(
                summary,
                topGenres,
                topActors,
                topDirectors,
                characterCard,
                flavorChanges,
                fourCut,
                genreShares,
                countryShares,
                eraShares,
                keywords,
                comparisons,
                highlights,
                characterAxisMedians,
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
                                            person.personId(),
                                            person.name(),
                                            person.originalName(),
                                            person.profileUrl()))
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

    /**
     * 3.4 · 7.4 `시대`. 상위 두 연대를 그대로 쓰고 **나머지 연대는 `그 이전` 한 줄로 합친다** (업무 -774). 세 번째 연대만 남기면 4위 이하가
     * 조용히 사라져 세 줄의 합이 무엇을 뜻하는지 알 수 없다.
     *
     * <p>`그 이전`은 이름과 달리 남은 연대 전부이며, 상위 두 줄보다 최근인 연대도 들어갈 수 있다. 개봉 연도가 없는 영화는 어느 줄에도 세지 않되 분모에는 남는다.
     * 합칠 나머지가 없으면 그 줄을 넣지 않는다.
     */
    private static List<Share> eraShares(List<ReportSource.Frame> frames, int total) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ReportSource.Frame frame : frames) {
            if (frame.releaseYear() != null) {
                int decade = Math.floorDiv(frame.releaseYear(), 10) * 10;
                counts.merge(decade, 1, Integer::sum);
            }
        }
        List<Map.Entry<Integer, Integer>> ranked =
                counts.entrySet().stream()
                        .sorted(
                                Map.Entry.<Integer, Integer>comparingByValue()
                                        .reversed()
                                        .thenComparing(
                                                Map.Entry.comparingByKey(
                                                        Comparator.reverseOrder())))
                        .toList();
        int named = Math.min(ERA_NAMED_LIMIT, ranked.size());
        List<Share> shares = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : ranked.subList(0, named)) {
            shares.add(new Share(entry.getKey() + "년대", percentage(entry.getValue(), total)));
        }
        int rest =
                ranked.subList(named, ranked.size()).stream().mapToInt(Map.Entry::getValue).sum();
        if (rest > 0) {
            shares.add(new Share(ERA_REST_LABEL, percentage(rest, total)));
        }
        return List.copyOf(shares);
    }

    /**
     * 3.4 · 7.4 `지난 반기와 비교`. 축은 **팝콘 맛**이다(업무 -774).
     *
     * <p>영화 한 편에 대표 맛이 하나라 반기 안의 비율을 더하면 100%가 된다. 장르는 한 편이 여러 장르에 중복으로 세어져 같은 자리에 두면 합이 100을 넘는다.
     * 3.3 인화 화면의 `새로 넓어진 팝콘 맛` 하이라이트도 같은 축이라 두 화면이 어긋나지 않는다.
     *
     * <p>대표 맛이 없는 프레임은 어느 항목에도 세지 않되 분모에는 남는다. `isNew`는 이번 반기 전 필름에 남아 있는 **모든** 프레임에서 그 맛을 찾을 수 없을
     * 때만 붙인다(계약 API-060).
     */
    private static List<Comparison> comparisons(
            List<ReportSource.Frame> currentFrames,
            List<ReportSource.Frame> previousFrames,
            Set<String> historicalCodes,
            int currentTotal,
            int previousTotal) {
        Map<String, Integer> currentCounts = flavorCounts(currentFrames);
        Map<String, Integer> previousCounts = flavorCounts(previousFrames);
        Map<String, String> labels = flavorLabels(currentFrames, previousFrames);
        Set<String> codes = new HashSet<>(currentCounts.keySet());
        codes.addAll(previousCounts.keySet());
        return codes.stream()
                .map(
                        code -> {
                            int currentCount = currentCounts.getOrDefault(code, 0);
                            int previousCount = previousCounts.getOrDefault(code, 0);
                            double delta =
                                    ratio(currentCount, currentTotal)
                                            - ratio(previousCount, previousTotal);
                            return new ComparisonCandidate(
                                    code,
                                    labels.getOrDefault(code, code),
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

    /** 맛 코드 → 표시명. 두 반기의 프레임이 실어 온 이름을 쓰고, 없으면 코드를 그대로 쓴다 */
    private static Map<String, String> flavorLabels(
            List<ReportSource.Frame> current, List<ReportSource.Frame> previous) {
        Map<String, String> labels = new HashMap<>();
        putFlavorLabels(labels, current);
        putFlavorLabels(labels, previous);
        return labels;
    }

    private static void putFlavorLabels(
            Map<String, String> labels, List<ReportSource.Frame> frames) {
        for (ReportSource.Frame frame : frames) {
            if (frame.flavorCode() != null) {
                labels.putIfAbsent(
                        frame.flavorCode(), flavorName(frame.flavorName(), frame.flavorCode()));
            }
        }
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
            List<GenreStat> genres,
            List<PersonStat> directors,
            List<PersonStat> actors,
            String characterName) {
        GenreCard genre =
                genres.isEmpty()
                        ? null
                        : new GenreCard(
                                genres.getFirst().name(),
                                genreDisplayName(genres.getFirst().code()),
                                new ImageRef(null));
        DirectorCard director =
                directors.isEmpty()
                        ? null
                        : new DirectorCard(
                                directors.getFirst().name(),
                                personDisplayName(
                                        directors.getFirst().name(),
                                        directors.getFirst().originalName()),
                                new ImageRef(directors.getFirst().profileUrl()),
                                null);
        PersonCard actor =
                actors.isEmpty()
                        ? null
                        : new PersonCard(
                                actors.getFirst().name(),
                                personDisplayName(
                                        actors.getFirst().name(), actors.getFirst().originalName()),
                                new ImageRef(actors.getFirst().profileUrl()));
        return new FourCut(genre, director, actor, new CharacterCutCard(characterName, null));
    }

    /**
     * people.original_name은 외국 인물의 라틴 원어명만 담는다. 한국 인물은 시안대로 한글 한 줄로 두려고 적재에서 제외하므로 여기서는 NULL로 들어온다.
     */
    static String personDisplayName(String name, String originalName) {
        if (originalName == null || originalName.isBlank() || originalName.equals(name)) {
            return null;
        }
        return originalName;
    }

    static String genreDisplayName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (code.startsWith("TMDB_")) {
            return TMDB_GENRE_DISPLAY_NAMES.get(code.substring("TMDB_".length()));
        }
        return code;
    }

    /**
     * 3.4 · 7.4 `취향 키워드`. 그 반기 집계에서 **수치로 뒷받침되는 것만** 만들고 근거 문장에 그 수치를 그대로 적는다(업무 -774).
     *
     * <p>규칙마다 임계값을 두고 **넘긴 것만** 아래 순서대로 최대 네 개를 고른다. 누구나 같은 네 칸이 뜨지 않게 하려는 것이라, 취향이 뚜렷하면 뚜렷한 칩이 뜨고
     * 고르게 본 반기는 두세 개만 뜨거나 하나도 안 뜬다. 하나도 못 넘기면 빈 배열이고 화면이 섹션을 숨긴다.
     *
     * <p>순서는 `사람 → 맛 → 장르 → 시대 → 평가 습관 → 국가`다. 앞쪽일수록 그 반기를 남의 반기와 가르는 힘이 세다고 보았다.
     *
     * <p>와이어프레임의 `계획형 안티히어로` · `빠른 전개` · `반전 선호`는 캐릭터 성격과 전개 태그가 있어야 하는데 그런 기준정보 테이블이 없다. 없는 근거를
     * 지어내지 않고 여기서 뺐다. 태그 기준정보가 생기면 규칙을 더한다.
     */
    private static List<Keyword> keywords(
            ReportPeriod period,
            int currentTotal,
            List<ReportSource.Frame> currentFrames,
            List<GenreStat> currentGenres,
            List<PersonStat> currentDirectors,
            List<PersonStat> currentActors,
            Map<String, String> newFlavors,
            int newDirectorCount,
            List<Share> eraShares,
            List<Share> countryShares,
            BigDecimal averageScore) {
        List<Keyword> keywords = new ArrayList<>();
        Map<String, Integer> flavorCounts = flavorCounts(currentFrames);
        Map<String, String> flavorLabels = flavorLabels(currentFrames, List.of());

        // 01 사람 — 한 감독·배우를 몰아본 반기
        if (!currentDirectors.isEmpty() && currentDirectors.getFirst().count() >= PERSON_BINGE) {
            PersonStat director = currentDirectors.getFirst();
            add(
                    keywords,
                    "DIRECTOR_BINGE",
                    director.name() + " 몰아보기",
                    director.name() + " 영화 " + director.count() + "편");
        }
        if (!currentActors.isEmpty() && currentActors.getFirst().count() >= PERSON_BINGE) {
            PersonStat actor = currentActors.getFirst();
            add(
                    keywords,
                    "ACTOR_FAVORITE",
                    actor.name() + " 최애",
                    actor.name() + " " + actor.count() + "편");
        }
        if (newDirectorCount >= NEW_DIRECTOR_MIN) {
            add(keywords, "NEW_DIRECTOR_HUNTER", "새 감독 발굴", "처음 본 감독 " + newDirectorCount + "명");
        }

        // 02 맛 — 넓혔는지 한 우물을 팠는지
        for (Map.Entry<String, String> flavor : newFlavors.entrySet()) {
            add(
                    keywords,
                    "FLAVOR_EXPLORER",
                    flavor.getValue() + " 탐험가",
                    "이번 반기에 "
                            + flavor.getValue()
                            + " 프레임 "
                            + flavorCounts.getOrDefault(flavor.getKey(), 0)
                            + "개 추가");
            break;
        }
        Map.Entry<String, Integer> topFlavor = top(flavorCounts);
        if (topFlavor != null) {
            int share = percentage(topFlavor.getValue(), currentTotal);
            if (share >= DEVOTEE_SHARE) {
                String name = flavorLabels.getOrDefault(topFlavor.getKey(), topFlavor.getKey());
                add(keywords, "FLAVOR_DEVOTEE", name + " 한 우물", name + "가 " + share + "%");
            }
        }

        // 03 장르 — 편식인지 잡식인지, 또는 특별히 높이 준 장르
        if (!currentGenres.isEmpty()) {
            GenreStat top = currentGenres.getFirst();
            int share = percentage(top.count(), currentTotal);
            if (share >= DEVOTEE_SHARE) {
                add(keywords, "GENRE_DEVOTEE", top.name() + " 편식", top.name() + "가 " + share + "%");
            }
            if (currentGenres.size() >= OMNIVORE_GENRES) {
                add(keywords, "GENRE_OMNIVORE", "장르 잡식", "이번 반기에 " + currentGenres.size() + "개 장르");
            }
            if (top.count() >= FAVORITE_MIN_COUNT
                    && top.averageScore().compareTo(FAVORITE_MIN_SCORE) >= 0) {
                add(
                        keywords,
                        "GENRE_FAVORITE",
                        top.name() + " 편애",
                        top.name()
                                + " "
                                + top.count()
                                + "편 평균 ★"
                                + top.averageScore()
                                        .setScale(1, RoundingMode.HALF_UP)
                                        .toPlainString());
            }
        }

        // 04 시대 — 고전을 팠는지 신작을 쫓았는지
        int classicCutoff = period.year() - CLASSIC_YEARS;
        int classicShare =
                percentage(countYears(currentFrames, year -> year <= classicCutoff), currentTotal);
        if (classicShare >= CLASSIC_SHARE) {
            add(
                    keywords,
                    "CLASSIC_DIGGER",
                    "고전 발굴가",
                    classicCutoff + "년 이전 영화가 " + classicShare + "%");
        }
        int recentCutoff = period.year() - 1;
        int recentShare =
                percentage(countYears(currentFrames, year -> year >= recentCutoff), currentTotal);
        if (recentShare >= RECENT_SHARE) {
            add(keywords, "NEW_RELEASE_CHASER", "신작 추적자", "최근 2년 영화가 " + recentShare + "%");
        }
        if (!eraShares.isEmpty() && eraShares.getFirst().share() >= ERA_LOVER_SHARE) {
            Share era = eraShares.getFirst();
            add(
                    keywords,
                    "ERA_LOVER",
                    era.label() + " 애호가",
                    era.label() + " 프레임이 " + era.share() + "%");
        }

        // 05 평가 습관
        String score = averageScore.setScale(1, RoundingMode.HALF_UP).toPlainString();
        if (averageScore.compareTo(GENEROUS_SCORE) >= 0) {
            add(keywords, "GENEROUS_RATER", "후한 평가자", "이번 반기 평균 ★" + score);
        } else if (averageScore.compareTo(STRICT_SCORE) <= 0) {
            add(keywords, "STRICT_RATER", "깐깐한 눈", "이번 반기 평균 ★" + score);
        }

        // 06 국가
        if (!countryShares.isEmpty() && countryShares.getFirst().share() >= COUNTRY_SHARE) {
            Share country = countryShares.getFirst();
            add(
                    keywords,
                    "COUNTRY_FOCUS",
                    country.label() + " 영화 집중",
                    country.label() + " 영화가 " + country.share() + "%");
        }

        return List.copyOf(keywords);
    }

    /** 네 개를 채웠으면 더 넣지 않는다. 우선순위는 부르는 순서가 정한다 */
    private static void add(List<Keyword> keywords, String code, String label, String rationale) {
        if (keywords.size() < KEYWORD_LIMIT) {
            keywords.add(new Keyword(code, label, rationale));
        }
    }

    private static Map.Entry<String, Integer> top(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Integer>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                .findFirst()
                .orElse(null);
    }

    private static int countYears(List<ReportSource.Frame> frames, IntPredicate match) {
        return (int)
                frames.stream()
                        .map(ReportSource.Frame::releaseYear)
                        .filter(year -> year != null)
                        .filter(match::test)
                        .count();
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

    private record PersonStat(
            long personId, String name, String originalName, String profileUrl, int count) {}

    private static final class MutablePersonStat {
        private final long personId;
        private final String name;
        private final String originalName;
        private final String profileUrl;
        private final Set<Long> frameIds = new HashSet<>();

        private MutablePersonStat(
                long personId, String name, String originalName, String profileUrl) {
            this.personId = personId;
            this.name = name;
            this.originalName = originalName;
            this.profileUrl = profileUrl;
        }

        private void add(long frameId) {
            frameIds.add(frameId);
        }

        private PersonStat freeze() {
            return new PersonStat(personId, name, originalName, profileUrl, frameIds.size());
        }
    }

    private record ComparisonCandidate(String code, String label, double delta, boolean isNew) {}
}
