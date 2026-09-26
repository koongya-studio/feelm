package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.dto.ReportGenerateResponse;
import com.feelm.backend.domain.report.dto.ReportListResponse.Generation;
import com.feelm.backend.domain.report.model.CharacterAxisMetrics;
import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.model.ReportHalf;
import com.feelm.backend.domain.report.model.ReportPeriod;
import com.feelm.backend.domain.report.model.ReportPresentation;
import com.feelm.backend.domain.report.model.ReportSource.Snapshot;
import com.feelm.backend.domain.report.repository.ReportGenerationRepository;
import com.feelm.backend.domain.report.repository.ReportManualRepository;
import com.feelm.backend.domain.report.repository.ReportReadRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportManualService {

    public static final int MINIMUM_FRAMES = 20;
    private static final Instant ALL_FRAMES = Instant.parse("9999-12-31T23:59:59Z");
    private final ReportManualRepository repository;
    private final ReportReadRepository readRepository;
    private final ReportGenerationRepository sources;
    private final ReportContentAssembler assembler;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReportManualService(
            ReportManualRepository repository,
            ReportReadRepository readRepository,
            ReportGenerationRepository sources,
            ReportContentAssembler assembler,
            ObjectMapper mapper,
            Clock clock,
            PlatformTransactionManager manager) {
        this.repository = repository;
        this.readRepository = readRepository;
        this.sources = sources;
        this.assembler = assembler;
        this.mapper = mapper;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ReportGenerateResponse generate(long userId) {
        // A busy attempt ends its transaction before retrying, so the winning report is visible in
        // the next snapshot. A serialization conflict also restarts the snapshot: the advisory-lock
        // statement can race a commit.
        for (int attempt = 0; attempt < 200; attempt++) {
            ReportGenerateResponse response;
            try {
                response =
                        transaction.execute(
                                status ->
                                        repository.tryLock(userId) ? generateLocked(userId) : null);
            } catch (org.springframework.dao.ConcurrencyFailureException racedSnapshot) {
                response = null;
            }
            if (response != null) {
                // A read-only reuse can succeed with an old SERIALIZABLE snapshot even after a
                // winner commits. Check the committed latest ID outside that transaction before
                // returning a reused result; unlike INSERT, reuse does not trigger an SSI abort.
                if (!response.reused()
                        || repository.latest(userId).orElseThrow().id() == response.reportId()) {
                    return response;
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.REPORT_GENERATING);
            }
        }
        throw new BusinessException(ErrorCode.REPORT_GENERATING);
    }

    public Generation availability(long userId) {
        var previous = repository.latest(userId).orElse(null);
        int count = repository.frameCount(userId);
        boolean enough = previous != null || count >= MINIMUM_FRAMES;
        boolean changed =
                previous == null
                        || !previous.fingerprint()
                                .equals(fingerprint(repository.filmState(userId)));
        return new Generation(
                count,
                MINIMUM_FRAMES,
                enough && changed,
                !enough ? "INSUFFICIENT_FRAMES" : !changed ? "UNCHANGED" : null);
    }

    private ReportGenerateResponse generateLocked(long userId) {
        var previous = repository.latest(userId).orElse(null);
        String fingerprint = fingerprint(repository.filmState(userId));
        if (previous != null && previous.fingerprint().equals(fingerprint)) {
            return new ReportGenerateResponse(
                    previous.id(),
                    previous.createdAt(),
                    true,
                    previous.issuedAt(),
                    "UNCHANGED_FILM");
        }
        if (previous == null && repository.frameCount(userId) < MINIMUM_FRAMES) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    Map.of("reason", "INSUFFICIENT_FRAMES", "minimumFrames", MINIMUM_FRAMES));
        }
        Snapshot snapshot = snapshot(userId);
        Snapshot before =
                previous == null ? null : mapper.readValue(previous.source(), Snapshot.class);
        List<CharacterAxisMetrics> population = new ArrayList<>();
        List<Long> eligible = repository.eligibleUsers();
        if (!eligible.isEmpty()) {
            var populationFrames =
                    sources.findFrames(eligible, ALL_FRAMES).stream()
                            .collect(
                                    java.util.stream.Collectors.groupingBy(
                                            com.feelm.backend.domain.report.model.ReportSource.Frame
                                                    ::userId));
            var priorSources = repository.previousSources(eligible);
            for (long subject : eligible) {
                var prior =
                        priorSources.containsKey(subject)
                                ? mapper.readValue(priorSources.get(subject), Snapshot.class)
                                : null;
                population.add(
                        CharacterCardSelector.cumulativeMetrics(
                                populationFrames.getOrDefault(subject, List.of()),
                                prior == null ? List.of() : prior.frames()));
            }
        }
        Instant now = clock.instant();
        var korea = now.atZone(ReportPeriod.KOREA);
        var period =
                new ReportPeriod(
                        korea.getYear(),
                        korea.getMonthValue() <= 6 ? ReportHalf.H1 : ReportHalf.H2);
        var content =
                assembler.assembleCumulative(
                        period, now, snapshot, before, CharacterCardSelector.medians(population));
        if (previous != null
                && samePresentation(
                        content, mapper.readValue(previous.content(), ReportContent.class))) {
            return new ReportGenerateResponse(
                    previous.id(), previous.createdAt(), true, previous.issuedAt(), "SAME_CONTENT");
        }
        long id =
                repository.insert(
                        userId,
                        period,
                        mapper.writeValueAsString(content),
                        fingerprint,
                        mapper.writeValueAsString(snapshot),
                        now);
        return new ReportGenerateResponse(id, now, false, null, null);
    }

    private Snapshot snapshot(long userId) {
        var ids = List.of(userId);
        return new Snapshot(
                sources.findFrames(ids, ALL_FRAMES),
                sources.findGenres(ids, ALL_FRAMES),
                sources.findCountries(ids, ALL_FRAMES),
                sources.findPeople(ids, ALL_FRAMES));
    }

    private boolean samePresentation(ReportContent current, ReportContent previous) {
        return ReportPresentation.from(
                        current, ReportDisplayResolver.fourCut(current, readRepository))
                .equals(
                        ReportPresentation.from(
                                previous, ReportDisplayResolver.fourCut(previous, readRepository)));
    }

    private static String fingerprint(String state) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            Objects.requireNonNull(state)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
