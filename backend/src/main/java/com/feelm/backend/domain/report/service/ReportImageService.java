package com.feelm.backend.domain.report.service;

import com.feelm.backend.domain.report.dto.response.ReportImageResponse;
import com.feelm.backend.domain.report.repository.ReportImageRepository;
import com.feelm.backend.domain.report.repository.ReportImageRepository.ImageRow;
import com.feelm.backend.domain.report.repository.ReportImageRepository.ReportSource;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReportImageService {

    private static final String FORMAT = "PNG";

    private final ReportImageRepository repository;
    private final ReportImageStorage storage;
    private final ReportImageUrlSigner signer;
    private final ReportImagePolicy policy;
    private final ReportImageLockManager locks;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ReportImageService(
            ReportImageRepository repository,
            ReportImageStorage storage,
            ReportImageUrlSigner signer,
            ReportImagePolicy policy,
            ReportImageLockManager locks,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.storage = storage;
        this.signer = signer;
        this.policy = policy;
        this.locks = locks;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ReportImageResponse create(long userId, long reportId, byte[] uploadedPng) {
        return locks.withLock(reportId, () -> createLocked(userId, reportId, uploadedPng));
    }

    /** 본문을 메모리에 올리기 전에 인가·상태를 검사하고 정상 기존 파일이면 재서명만 한다. */
    public ReportImageResponse reuseExistingOrReject(long userId, long reportId) {
        return locks.withLock(
                reportId,
                () -> {
                    Instant now = clock.instant();
                    ImageRow image = inspect(userId, reportId, now).image();
                    if (image == null) {
                        return null;
                    }
                    if (storage.exists(image.storageKey())) {
                        return response(image.id());
                    }
                    if (now.isBefore(image.createdAt().plus(policy.regenerationInterval()))) {
                        throw new BusinessException(ErrorCode.RATE_LIMITED);
                    }
                    return null;
                });
    }

    private ReportImageResponse createLocked(long userId, long reportId, byte[] uploadedPng) {
        Instant now = clock.instant();
        Inspection inspection = inspect(userId, reportId, now);
        ImageRow image = inspection.image();
        storage.validate(uploadedPng);
        if (image != null && storage.exists(image.storageKey())) {
            return response(image.id());
        }

        if (image != null && now.isBefore(image.createdAt().plus(policy.regenerationInterval()))) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        if (image == null) {
            String storageKey = storage.store(reportId, uploadedPng);
            PersistedImage persisted;
            try {
                persisted = persistNew(userId, reportId, storageKey, now);
            } catch (RuntimeException exception) {
                compensateDelete(storageKey, exception);
                throw exception;
            }
            if (!persisted.inserted()) {
                storage.delete(storageKey);
            }
            return response(persisted.image().id());
        }

        try {
            storage.restore(image.storageKey(), uploadedPng);
            touchRestored(userId, reportId, image.id(), now);
        } catch (RuntimeException exception) {
            compensateDelete(image.storageKey(), exception);
            throw exception;
        }
        return response(image.id());
    }

    public byte[] download(long imageId, String expiresValue, String signature) {
        long expiresEpochSecond;
        try {
            expiresEpochSecond = Long.parseLong(expiresValue);
        } catch (NumberFormatException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (signature == null || !signer.verify(imageId, expiresEpochSecond, signature)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        ImageRow image =
                repository
                        .findById(imageId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return storage.load(image.storageKey());
    }

    private Inspection inspect(long userId, long reportId, Instant now) {
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            ReportSource report = ownedLocked(userId, reportId, now);
                            ImageRow image = repository.findByReportId(reportId).orElse(null);
                            return new Inspection(report, image);
                        }));
    }

    private PersistedImage persistNew(
            long userId, long reportId, String storageKey, Instant createdAt) {
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            ownedLocked(userId, reportId, createdAt);
                            ImageRow existing = repository.findByReportId(reportId).orElse(null);
                            if (existing != null) {
                                return new PersistedImage(existing, false);
                            }
                            long imageId = repository.insert(reportId, storageKey, createdAt);
                            return new PersistedImage(
                                    new ImageRow(imageId, reportId, storageKey, createdAt), true);
                        }));
    }

    private void touchRestored(long userId, long reportId, long imageId, Instant createdAt) {
        transactions.executeWithoutResult(
                status -> {
                    ownedLocked(userId, reportId, createdAt);
                    ImageRow current =
                            repository
                                    .findByReportId(reportId)
                                    .filter(image -> image.id() == imageId)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ErrorCode.RESOURCE_NOT_FOUND));
                    repository.touch(current.id(), createdAt);
                });
    }

    private ReportSource ownedLocked(long userId, long reportId, Instant now) {
        ReportSource report =
                repository
                        .lockOwned(userId, reportId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        requireAvailable(report, now);
        return report;
    }

    private void requireAvailable(ReportSource report, Instant now) {
        if (!report.manual() && now.isBefore(report.period().publicationAt())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!"READY".equals(report.status())) {
            throw new BusinessException(ErrorCode.REPORT_GENERATING);
        }
    }

    private ReportImageResponse response(long imageId) {
        ReportImageUrlSigner.SignedUrl signed = signer.issue(imageId);
        return new ReportImageResponse(imageId, FORMAT, signed.url(), signed.expiresAt());
    }

    private void compensateDelete(String storageKey, RuntimeException original) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private record Inspection(ReportSource report, ImageRow image) {}

    private record PersistedImage(ImageRow image, boolean inserted) {}
}
