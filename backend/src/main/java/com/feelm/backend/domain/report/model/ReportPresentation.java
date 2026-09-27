package com.feelm.backend.domain.report.model;

import java.util.List;
import java.util.Objects;

/** API-086 중복 비교: 실제 4컷과 뒷면에 표시하는 내용. 생성 시각과 숨은 집계값은 제외한다. */
public record ReportPresentation(
        ReportContent.FourCut fourCut,
        List<ReportContent.Share> genreShares,
        List<ReportContent.Share> countryShares,
        List<ReportContent.Share> eraShares,
        List<Keyword> keywords,
        List<Person> actors,
        List<Person> directors,
        Character character,
        List<ReportContent.Comparison> comparisons) {

    public static ReportPresentation from(ReportContent content) {
        return from(content, content.fourCut());
    }

    public static ReportPresentation from(
            ReportContent content, ReportContent.FourCut displayedFourCut) {
        var card = content.characterCard();
        Character character =
                card == null
                        ? null
                        : new Character(
                                card.code(),
                                card.name(),
                                card.tagline(),
                                card.description(),
                                card.axes(),
                                card.basis() != null && card.basis().lowSample()
                                        ? card.basis().frameCount()
                                        : null);
        return new ReportPresentation(
                displayedFourCut,
                list(content.genreShares()),
                list(content.countryShares()),
                list(content.eraShares()),
                list(content.keywords()).stream()
                        .map(k -> new Keyword(k.label(), k.rationale()))
                        .toList(),
                people(content.topActors()),
                people(content.topDirectors()),
                character,
                list(content.comparisons()));
    }

    /** 앞면 캐릭터 그림은 이름 외에도 고정 카드 코드로 결정된다. */
    public boolean hasSameFront(ReportPresentation previous) {
        return Objects.equals(fourCut, previous.fourCut)
                && Objects.equals(
                        character == null ? null : character.code(),
                        previous.character == null ? null : previous.character.code());
    }

    private static List<Person> people(List<ReportContent.PersonRank> ranks) {
        return list(ranks).stream().map(p -> new Person(p.name(), p.count())).toList();
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record Person(String name, int count) {}

    public record Keyword(String label, String rationale) {}

    public record Character(
            String code,
            String name,
            String tagline,
            String description,
            List<ReportContent.AxisJudgement> axes,
            Integer lowSampleFrameCount) {}
}
