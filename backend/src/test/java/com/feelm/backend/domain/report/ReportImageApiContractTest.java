package com.feelm.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.report.repository.ReportImageRepository;
import com.feelm.backend.domain.report.service.ReportImageLockManager;
import com.feelm.backend.domain.report.service.ReportImageStorage;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("리포트 이미지 생성·다운로드 API — API-061")
class ReportImageApiContractTest extends ReportApiContractSupport {

    private static final long REPORT_ID = 5_570_101L;
    private static final byte[] VALID_PNG = png(1080, 3268);

    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private ReportImageStorage storage;
    @MockitoSpyBean private ReportImageLockManager lockManager;
    @MockitoSpyBean private ReportImageRepository repository;

    @Test
    @DisplayName("본인 READY 리포트를 비공개 PNG로 만들고 10분 서명 URL을 반환한다")
    void storesUploadedFrontendPngAndReturnsSignedUrl() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");

        JsonNode created = create(REPORT_ID);
        assertThat(created.path("imageId").asLong()).isPositive();
        assertThat(created.path("format").asText()).isEqualTo("PNG");
        assertThat(created.path("expiresAt").asText()).isEqualTo("2030-06-30T15:10:00Z");
        assertThat(created.path("downloadUrl").asText())
                .startsWith("/api/v1/report-images/")
                .contains("expires=1909062600")
                .contains("signature=");

        assertThat(imageCount(REPORT_ID)).isEqualTo(1);
        byte[] png =
                mockMvc.perform(get(URI.create(created.path("downloadUrl").asText())))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType("image/png"))
                        .andExpect(header().string("Cache-Control", "private, no-store"))
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                org.hamcrest.Matchers.startsWith("attachment;")))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded.getWidth()).isEqualTo(1080);
        assertThat(decoded.getHeight()).isEqualTo(3268);
        assertThat(png).isEqualTo(VALID_PNG);
    }

    @Test
    @DisplayName("손상됐거나 프론트 출력 규격이 아닌 PNG는 저장하지 않는다")
    void rejectsInvalidPngUpload() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");

        for (byte[] invalid : List.of(new byte[] {1, 2, 3}, png(1080, 3200))) {
            mockMvc.perform(upload(REPORT_ID).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REPORT_IMAGE_INVALID"));
        }
        assertThat(imageCount(REPORT_ID)).isZero();

        mockMvc.perform(
                        post("/api/v1/reports/{reportId}/image", REPORT_ID)
                                .header("Authorization", bearer(OWNER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("25MB를 넘는 요청은 제한된 길이만 읽고 413으로 거절한다")
    void rejectsOversizedPngUpload() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");

        mockMvc.perform(upload(REPORT_ID).content(new byte[ReportImageStorage.MAX_PNG_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REPORT_IMAGE_TOO_LARGE"));

        assertThat(imageCount(REPORT_ID)).isZero();
    }

    @Test
    @DisplayName("기존 파일은 다시 렌더링하지 않고 같은 imageId에 새 URL을 발급한다")
    void reusesStoredImageAndRefreshesSignedUrl() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");

        JsonNode first = create(REPORT_ID);
        clock.set(RELEASE.plusSeconds(20));
        JsonNode second = create(REPORT_ID);

        assertThat(second.path("imageId").asLong()).isEqualTo(first.path("imageId").asLong());
        assertThat(second.path("downloadUrl").asText())
                .isNotEqualTo(first.path("downloadUrl").asText());
        assertThat(second.path("expiresAt").asText()).isEqualTo("2030-06-30T15:10:20Z");
        assertThat(imageCount(REPORT_ID)).isEqualTo(1);
        verify(storage, times(1)).store(eq(REPORT_ID), any(byte[].class));
    }

    @Test
    @DisplayName("없는 리포트와 타인 리포트는 같은 404로 숨기고 생성 중은 409로 거절한다")
    void protectsOwnershipAndReadyState() throws Exception {
        users();
        report(REPORT_ID, OTHER, 2030, "H1", "READY");
        report(REPORT_ID + 1, OWNER, 2030, "H1", "GENERATING");

        for (long reportId : List.of(REPORT_ID, REPORT_ID + 99)) {
            mockMvc.perform(upload(reportId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mockMvc.perform(upload(REPORT_ID + 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_GENERATING"));
        mockMvc.perform(
                        post("/api/v1/reports/{reportId}/image", REPORT_ID + 1)
                                .contentType(MediaType.IMAGE_PNG)
                                .content(VALID_PNG))
                .andExpect(status().isUnauthorized());
        assertThat(imageCount(REPORT_ID)).isZero();
    }

    @Test
    @DisplayName("READY 리포트도 공개 시각 전에는 숨기고 정각부터 생성한다")
    void hidesUnpublishedReportUntilPublicationBoundary() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");

        clock.set(RELEASE.minusNanos(1));
        mockMvc.perform(upload(REPORT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        clock.set(RELEASE);
        create(REPORT_ID);
        assertThat(imageCount(REPORT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("유실 파일은 1분 이내 재생성을 막고 간격 뒤 같은 메타데이터로 복구한다")
    void rateLimitsEarlyRegenerationAndRecoversMissingFile() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        JsonNode created = create(REPORT_ID);
        long imageId = created.path("imageId").asLong();
        String key = storageKey(imageId);
        storage.delete(key);

        mockMvc.perform(upload(REPORT_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(storage.exists(key)).isFalse();

        clock.set(RELEASE.plusSeconds(60));
        JsonNode recovered = create(REPORT_ID);
        assertThat(recovered.path("imageId").asLong()).isEqualTo(imageId);
        assertThat(storage.exists(key)).isTrue();
        assertThat(imageCount(REPORT_ID)).isEqualTo(1);
        verify(storage).restore(eq(key), any(byte[].class));
    }

    @Test
    @DisplayName("다운로드는 유효한 서명만 허용하고 누락·변조·만료·유실을 구분한다")
    void requiresValidUnexpiredSignature() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        JsonNode created = create(REPORT_ID);
        long imageId = created.path("imageId").asLong();
        URI valid = URI.create(created.path("downloadUrl").asText());

        mockMvc.perform(get("/api/v1/report-images/{imageId}/download", imageId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(
                        get("/api/v1/report-images/{imageId}/download", imageId)
                                .param("expires", "not-a-number")
                                .param("signature", "invalid"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        String tampered = valid.toString().replace("signature=", "signature=A");
        mockMvc.perform(get(URI.create(tampered)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        clock.set(Instant.parse(created.path("expiresAt").asText()));
        mockMvc.perform(get(valid))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        clock.set(RELEASE);
        storage.delete(storageKey(imageId));
        mockMvc.perform(get(valid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("저장·조회 I/O 장애는 요청 제한으로 숨기지 않고 503을 반환한다")
    void reportsStorageFailuresAsUnavailable() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        doThrow(new BusinessException(ErrorCode.REPORT_IMAGE_STORAGE_UNAVAILABLE))
                .when(storage)
                .store(eq(REPORT_ID), any(byte[].class));

        mockMvc.perform(upload(REPORT_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_IMAGE_STORAGE_UNAVAILABLE"));
        assertThat(imageCount(REPORT_ID)).isZero();
    }

    @Test
    @DisplayName("기존 파일 상태 확인 장애는 429가 아니라 503을 반환한다")
    void doesNotMisclassifyStorageProbeFailureAsRateLimit() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        String key = "0b649505-0917-41d9-a41f-0d4402ec63d5.png";
        jdbc.update(
                """
                INSERT INTO report_images (report_id, storage_key, format, created_at)
                VALUES (?, ?, 'PNG', ?)
                """,
                REPORT_ID,
                key,
                Timestamp.from(RELEASE));
        doThrow(new BusinessException(ErrorCode.REPORT_IMAGE_STORAGE_UNAVAILABLE))
                .when(storage)
                .exists(key);

        mockMvc.perform(upload(REPORT_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_IMAGE_STORAGE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("서명 다운로드 중 저장소 읽기 장애도 503을 반환한다")
    void reportsDownloadStorageFailureAsUnavailable() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        JsonNode created = create(REPORT_ID);
        long imageId = created.path("imageId").asLong();
        String key = storageKey(imageId);
        doThrow(new BusinessException(ErrorCode.REPORT_IMAGE_STORAGE_UNAVAILABLE))
                .when(storage)
                .load(key);

        mockMvc.perform(get(URI.create(created.path("downloadUrl").asText())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_IMAGE_STORAGE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("DB 저장 실패는 먼저 쓴 새 파일을 보상 삭제한다")
    void deletesNewFileWhenMetadataInsertFails() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        doThrow(new DataAccessResourceFailureException("simulated insert failure"))
                .when(repository)
                .insert(eq(REPORT_ID), anyString(), any(Instant.class));

        mockMvc.perform(upload(REPORT_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(imageCount(REPORT_ID)).isZero();
        verify(storage).delete(anyString());
    }

    @Test
    @DisplayName("DB 실패 뒤 보상 삭제도 실패하면 최초 DB 오류를 유지한다")
    void preservesOriginalFailureWhenCompensationFails() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        doThrow(new DataAccessResourceFailureException("simulated insert failure"))
                .when(repository)
                .insert(eq(REPORT_ID), anyString(), any(Instant.class));
        doThrow(new BusinessException(ErrorCode.REPORT_IMAGE_STORAGE_UNAVAILABLE))
                .when(storage)
                .delete(anyString());

        mockMvc.perform(upload(REPORT_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(imageCount(REPORT_ID)).isZero();
        verify(storage).delete(anyString());
    }

    @Test
    @DisplayName("같은 리포트 동시 생성은 한 파일과 한 DB 행로 수렴한다")
    void concurrentCreationConvergesOnOneImage() throws Exception {
        users();
        report(REPORT_ID, OWNER, 2030, "H1", "READY");
        CountDownLatch storingStarted = new CountDownLatch(1);
        CountDownLatch secondEnteredLockManager = new CountDownLatch(1);
        CountDownLatch releaseStorage = new CountDownLatch(1);
        AtomicInteger lockAttempts = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            // 첫 요청은 preflight와 create에서 두 번 잠근다. 세 번째가 두 번째 요청이다.
                            if (lockAttempts.incrementAndGet() == 3) {
                                secondEnteredLockManager.countDown();
                            }
                            return invocation.callRealMethod();
                        })
                .when(lockManager)
                .withLock(eq(REPORT_ID), any());
        doAnswer(
                        invocation -> {
                            storingStarted.countDown();
                            assertThat(releaseStorage.await(5, TimeUnit.SECONDS)).isTrue();
                            return invocation.callRealMethod();
                        })
                .when(storage)
                .store(eq(REPORT_ID), any(byte[].class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> createResult(REPORT_ID));
            assertThat(storingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> second = executor.submit(() -> createResult(REPORT_ID));
            assertThat(secondEnteredLockManager.await(5, TimeUnit.SECONDS)).isTrue();
            releaseStorage.countDown();

            JsonNode firstBody = body(first.get(10, TimeUnit.SECONDS));
            JsonNode secondBody = body(second.get(10, TimeUnit.SECONDS));
            assertThat(firstBody.path("imageId").asLong())
                    .isEqualTo(secondBody.path("imageId").asLong());
            assertThat(imageCount(REPORT_ID)).isEqualTo(1);
            verify(storage, times(1)).store(eq(REPORT_ID), any(byte[].class));
        } finally {
            releaseStorage.countDown();
            executor.shutdownNow();
        }
    }

    private JsonNode create(long reportId) throws Exception {
        return body(createResult(reportId));
    }

    private MvcResult createResult(long reportId) throws Exception {
        return mockMvc.perform(upload(reportId)).andExpect(status().isCreated()).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private long imageCount(long reportId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM report_images WHERE report_id = ?", Long.class, reportId);
    }

    private String storageKey(long imageId) {
        return jdbc.queryForObject(
                "SELECT storage_key FROM report_images WHERE id = ?", String.class, imageId);
    }

    private MockHttpServletRequestBuilder upload(long reportId) {
        return post("/api/v1/reports/{reportId}/image", reportId)
                .header("Authorization", bearer(OWNER))
                .contentType(MediaType.IMAGE_PNG)
                .content(VALID_PNG);
    }

    private static byte[] png(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
