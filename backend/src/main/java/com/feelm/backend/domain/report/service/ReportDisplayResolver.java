package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.repository.ReportReadRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 상세 조회와 중복 판정에서 같은 현재 표시 이름을 사용한다. 저장된 결과는 수정하지 않는다. */
public final class ReportDisplayResolver {
    private ReportDisplayResolver() {}

    /**
     * Resolve person display names from the current people table on every read. This both fills old
     * READY reports and clears a stored value if the person classification is corrected.
     */
    public static ReportContent.FourCut fourCut(
            ReportContent content, ReportReadRepository repository) {
        ReportContent.FourCut fourCut = content.fourCut();
        if (fourCut == null) {
            return null;
        }

        ReportContent.GenreCard genre = genreWithDisplayName(content, fourCut.genre());
        Map<Long, String> originalNames = originalNames(content, fourCut, repository);
        ReportContent.DirectorCard director = fourCut.director();
        if (director != null && leadRank(content.topDirectors(), director.name()) != null) {
            String displayName =
                    personDisplayName(content.topDirectors(), director.name(), originalNames);
            if (!Objects.equals(displayName, director.displayName())) {
                director =
                        new ReportContent.DirectorCard(
                                director.name(), displayName, director.image(), director.work());
            }
        }
        ReportContent.PersonCard actor = fourCut.actor();
        if (actor != null && leadRank(content.topActors(), actor.name()) != null) {
            String displayName =
                    personDisplayName(content.topActors(), actor.name(), originalNames);
            if (!Objects.equals(displayName, actor.displayName())) {
                actor = new ReportContent.PersonCard(actor.name(), displayName, actor.image());
            }
        }

        if (genre == fourCut.genre()
                && director == fourCut.director()
                && actor == fourCut.actor()) {
            return fourCut;
        }
        return new ReportContent.FourCut(genre, director, actor, fourCut.character());
    }

    private static ReportContent.GenreCard genreWithDisplayName(
            ReportContent content, ReportContent.GenreCard genre) {
        if (genre == null
                || !needsDisplayName(genre.displayName())
                || content.topGenres() == null
                || content.topGenres().isEmpty()) {
            return genre;
        }
        ReportContent.GenreRank representative = content.topGenres().getFirst();
        if (!Objects.equals(genre.name(), representative.name())) {
            return genre;
        }
        String displayName = ReportContentAssembler.genreDisplayName(representative.code());
        return displayName == null
                ? genre
                : new ReportContent.GenreCard(genre.name(), displayName, genre.image());
    }

    /** Looks up both cards at once so a detail read costs at most one extra query. */
    private static Map<Long, String> originalNames(
            ReportContent content, ReportContent.FourCut fourCut, ReportReadRepository repository) {
        Set<Long> personIds = new LinkedHashSet<>();
        if (fourCut.director() != null) {
            addLeadPersonId(personIds, content.topDirectors(), fourCut.director().name());
        }
        if (fourCut.actor() != null) {
            addLeadPersonId(personIds, content.topActors(), fourCut.actor().name());
        }
        return personIds.isEmpty()
                ? Map.of()
                : repository.findOriginalNames(new ArrayList<>(personIds));
    }

    private static void addLeadPersonId(
            Set<Long> personIds, List<ReportContent.PersonRank> ranks, String cardName) {
        ReportContent.PersonRank lead = leadRank(ranks, cardName);
        if (lead != null) {
            personIds.add(lead.personId());
        }
    }

    private static String personDisplayName(
            List<ReportContent.PersonRank> ranks,
            String cardName,
            Map<Long, String> originalNames) {
        ReportContent.PersonRank lead = leadRank(ranks, cardName);
        if (lead == null) {
            return null;
        }
        return ReportContentAssembler.personDisplayName(
                cardName, originalNames.get(lead.personId()));
    }

    /** The four-cut card always shows the top-ranked person, so only that row can identify it. */
    private static ReportContent.PersonRank leadRank(
            List<ReportContent.PersonRank> ranks, String cardName) {
        if (ranks == null || ranks.isEmpty()) {
            return null;
        }
        ReportContent.PersonRank lead = ranks.getFirst();
        return Objects.equals(cardName, lead.name()) ? lead : null;
    }

    private static boolean needsDisplayName(String displayName) {
        return displayName == null || displayName.isBlank();
    }
}
