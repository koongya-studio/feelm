package com.feelm.backend.domain.report.dto;

import com.feelm.backend.domain.report.model.ReportContent.CharacterCardContent;
import com.feelm.backend.domain.report.model.ReportContent.Comparison;
import com.feelm.backend.domain.report.model.ReportContent.FlavorChange;
import com.feelm.backend.domain.report.model.ReportContent.FourCut;
import com.feelm.backend.domain.report.model.ReportContent.GenreRank;
import com.feelm.backend.domain.report.model.ReportContent.Keyword;
import com.feelm.backend.domain.report.model.ReportContent.PersonRank;
import com.feelm.backend.domain.report.model.ReportContent.Share;
import com.feelm.backend.domain.report.model.ReportContent.Summary;
import com.feelm.backend.domain.report.model.ReportPeriod;
import java.time.Instant;
import java.util.List;

/**
 * API-060 응답. 필드를 하나씩 나열하므로 {@code content}의 내부 메타데이터는 응답에 새지 않는다 — 캐릭터 카드 판정 기준인 {@code
 * characterAxisMedians}와 {@code generation}이 그렇다(API-060 처리 규칙).
 */
public record ReportDetailResponse(
        long reportId,
        ReportPeriod period,
        Summary summary,
        List<GenreRank> topGenres,
        List<PersonRank> topActors,
        List<PersonRank> topDirectors,
        CharacterCardContent characterCard,
        List<FlavorChange> flavorChanges,
        Instant createdAt,
        FourCut fourCut,
        List<Share> genreShares,
        List<Share> countryShares,
        List<Share> eraShares,
        List<Keyword> keywords,
        List<Comparison> comparisons,
        List<String> highlights,
        String analysisScope) {}
