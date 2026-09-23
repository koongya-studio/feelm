package com.feelm.backend.infrastructure.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("비공개 로컬 리포트 이미지 저장소")
class LocalReportImageStorageTest {

    @TempDir Path temporary;

    private LocalReportImageStorage storage;
    private Path storageRoot;

    @BeforeEach
    void setUp() {
        storageRoot = temporary.resolve("images");
        ReportImageProperties properties =
                new ReportImageProperties(
                        storageRoot,
                        "report-image-signing-key-at-least-32-bytes",
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(1),
                        "/api/v1/report-images");
        storage = new LocalReportImageStorage(properties);
        storage.prepare();
    }

    @Test
    @DisplayName("프론트 PNG를 서버 상대 키로 원자 저장하고 같은 키를 복구 덮어쓴다")
    void storesAndRestoresFrontendPng() {
        byte[] original = png((byte) 1);
        byte[] restored = png((byte) 2);

        String key = storage.store(557, original);
        assertThat(key).matches("[0-9a-f-]{36}\\.png");
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.load(key)).isEqualTo(original);

        storage.restore(key, restored);
        assertThat(storage.load(key)).isEqualTo(restored);
        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    @DisplayName("손상·규격 밖·25MB 초과 업로드를 구분해 거절한다")
    void validatesFrontendPngBoundary() {
        assertThatThrownBy(() -> storage.validate(new byte[] {1, 2, 3}))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.REPORT_IMAGE_INVALID));
        assertThatThrownBy(() -> storage.validate(png(1080, 3200, (byte) 1)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.REPORT_IMAGE_INVALID));
        assertThatThrownBy(() -> storage.validate(new byte[25 * 1024 * 1024 + 1]))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.REPORT_IMAGE_TOO_LARGE));
    }

    @Test
    @DisplayName("경로 이동이나 임의 파일명은 저장 루트 밖을 읽지 못한다")
    void rejectsUnsafeStorageKey() throws Exception {
        Path outside = temporary.resolve("outside.png");
        Files.write(outside, new byte[] {1, 2, 3});

        assertThatThrownBy(() -> storage.load("../outside.png"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("파일 부재와 저장소 경로 I/O 장애를 구분한다")
    void distinguishesMissingFileFromStorageFailure() throws Exception {
        String missing = "0b649505-0917-41d9-a41f-0d4402ec63d5.png";
        assertThat(storage.exists(missing)).isFalse();

        Files.delete(storageRoot);
        Files.write(storageRoot, new byte[] {1});
        assertThatThrownBy(() -> storage.exists(missing))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.REPORT_IMAGE_STORAGE_UNAVAILABLE));
    }

    @Test
    @DisplayName("일반 파일이나 손상된 PNG를 재사용하지 않는다")
    void rejectsCorruptPng() throws Exception {
        String key = storage.store(557, png((byte) 1));
        Files.write(
                storageRoot.resolve(key),
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1});

        assertThat(storage.exists(key)).isFalse();
        assertThatThrownBy(() -> storage.load(key))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private byte[] png(byte payload) {
        return png(1080, 3268, payload);
    }

    private byte[] png(int width, int height, byte payload) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(payload & 0xff, 20, 30));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
