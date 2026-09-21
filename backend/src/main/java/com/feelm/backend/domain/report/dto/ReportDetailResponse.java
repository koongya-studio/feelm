package com.feelm.backend.domain.report.dto;

import com.feelm.backend.domain.report.model.ReportContent.Comparison;
import com.feelm.backend.domain.report.model.ReportContent.FlavorChange;
import com.feelm.backend.domain.report.model.ReportContent.FourCut;
import com.feelm.backend.domain.report.model.ReportContent.GenreRank;
import com.feelm.backend.domain.report.model.ReportContent.PersonRank;
import com.feelm.backend.domain.report.model.ReportContent.Share;
import com.feelm.backend.domain.report.model.ReportContent.Summary;
import com.feelm.backend.domain.report.model.ReportPeriod;
import java.time.Instant;
import java.util.List;

public record ReportDetailResponse(
        long reportId,
        ReportPeriod period,
        Summary summary,
        List<GenreRank> topGenres,
        List<PersonRank> topActors,
        List<PersonRank> topDirectors,
        Object characterCard,
        List<FlavorChange> flavorChanges,
        Instant createdAt,
        FourCut fourCut,
        List<Share> genreShares,
        List<Share> countryShares,
        List<Share> eraShares,
        List<Object> keywords,
        List<Comparison> comparisons,
        List<String> highlights) {}
