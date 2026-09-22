package com.feelm.backend.domain.report.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReportContent(
        Summary summary,
        List<GenreRank> topGenres,
        List<PersonRank> topActors,
        List<PersonRank> topDirectors,
        CharacterCardContent characterCard,
        List<FlavorChange> flavorChanges,
        FourCut fourCut,
        List<Share> genreShares,
        List<Share> countryShares,
        List<Share> eraShares,
        List<Keyword> keywords,
        List<Comparison> comparisons,
        List<String> highlights,
        CharacterAxisMedians characterAxisMedians,
        Generation generation) {

    public record Summary(int ratingCount, int newFlavorCount, BigDecimal averageScore) {}

    public record GenreRank(String code, String name, int count) {}

    public record PersonRank(long personId, String name, int count) {}

    public record FlavorChange(String flavorCode, int before, int after) {}

    public record FourCut(
            GenreCard genre, DirectorCard director, PersonCard actor, CharacterCutCard character) {}

    public record ImageRef(String url) {}

    public record GenreCard(String name, String displayName, ImageRef image) {}

    public record DirectorCard(String name, String displayName, ImageRef image, Object work) {}

    public record PersonCard(String name, String displayName, ImageRef image) {}

    /**
     * 3.4 · 7.4 `취향 키워드` 한 개. `rationale`은 그 반기 집계에서 나온 수치를 그대로 적은 근거 문장이다. 성격 · 전개 태그는 기준정보가 없어 쓰지
     * 않는다(업무 -774).
     */
    public record Keyword(String code, String label, String rationale) {}

    /**
     * 4컷 04 칸. 다른 세 칸과 달리 {@code image}가 없다 — 캐릭터 카드가 고정 16종이라 그림은 저장소 에셋이고 화면이 {@code
     * characterCard.code}로 짝짓는다(API-060, 업무 -618).
     */
    public record CharacterCutCard(String name, String displayName) {}

    /**
     * {@code characterCard.axes} 한 축. 순서는 코드의 글자 순서와 같다.
     *
     * @param axis EXPLORATION · TONE · STANCE · VOLUME
     * @param value 그 축의 판정 결과 한 글자
     * @param label 사용자에게 보이는 축 값 이름
     */
    public record AxisJudgement(String axis, String value, String label) {}

    /** {@code characterCard.basis}. {@code frameCount}가 10 미만이면 {@code lowSample}이 참이다 */
    public record CharacterBasis(int frameCount, boolean lowSample) {}

    /**
     * API-060 {@code characterCard}. 고정 16종 중 하나다.
     *
     * <p>계약은 {@code code}뿐이고 이름 · 한 줄 · 설명문은 바뀔 수 있는 기준정보다({@code CharacterCard}).
     */
    public record CharacterCardContent(
            String code,
            String name,
            String tagline,
            String description,
            List<AxisJudgement> axes,
            CharacterBasis basis) {}

    public record Share(String label, int share) {}

    public record Comparison(String label, int delta, boolean isNew) {}

    public record Generation(
            int contentVersion, Instant snapshotAt, Instant cutoffAt, boolean finalSnapshot) {}
}
