package com.feelm.backend.infrastructure.report;

import com.feelm.backend.domain.report.service.ReportImageStorage;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class LocalReportImageStorage implements ReportImageStorage {

    private static final Pattern STORAGE_KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png");
    private static final int REQUIRED_WIDTH = 1080;
    private static final int REQUIRED_HEIGHT = 3268;

    private final ReportImageProperties properties;

    @PostConstruct
    void prepare() {
        try {
            Files.createDirectories(properties.storagePath());
            makePrivateDirectory(properties.storagePath());
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void validate(byte[] png) {
        if (png == null) {
            throw new BusinessException(ErrorCode.REPORT_IMAGE_INVALID);
        }
        if (png.length > MAX_PNG_BYTES) {
            throw new BusinessException(ErrorCode.REPORT_IMAGE_TOO_LARGE);
        }
        if (!isValidPng(png)) {
            throw new BusinessException(ErrorCode.REPORT_IMAGE_INVALID);
        }
    }

    @Override
    public String store(long reportId, byte[] png) {
        String storageKey = UUID.randomUUID() + ".png";
        write(storageKey, png);
        return storageKey;
    }

    @Override
    public void restore(String storageKey, byte[] png) {
        write(storageKey, png);
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(
                            resolve(storageKey),
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_PNG_BYTES) {
                return false;
            }
            return isValidPng(Files.readAllBytes(resolve(storageKey)));
        } catch (NoSuchFileException exception) {
            return missingFromAvailableRoot(exception);
        } catch (IOException | SecurityException exception) {
            throw unavailable(exception);
        }
    }

    private boolean missingFromAvailableRoot(NoSuchFileException missingFile) {
        try {
            BasicFileAttributes root =
                    Files.readAttributes(
                            properties.storagePath(),
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
            if (!root.isDirectory()) {
                throw unavailable(missingFile);
            }
            return false;
        } catch (IOException | SecurityException storageFailure) {
            throw unavailable(storageFailure);
        }
    }

    @Override
    public byte[] load(String storageKey) {
        try {
            byte[] png = Files.readAllBytes(resolve(storageKey));
            if (png.length > MAX_PNG_BYTES || !isValidPng(png)) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return png;
        } catch (NoSuchFileException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException | BusinessException exception) {
            throw unavailable(exception);
        }
    }

    private void write(String storageKey, byte[] png) {
        validate(png);
        Path target = resolve(storageKey);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(properties.storagePath(), ".report-image-", ".tmp");
            Files.write(temporary, png);
            makePrivateFile(temporary);
            move(temporary, target);
        } catch (IOException exception) {
            deleteTemporary(temporary);
            throw unavailable(exception);
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Path root = properties.storagePath().normalize();
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.getParent().equals(root)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return resolved;
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void makePrivateDirectory(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException exception) {
            // Windows local development has no POSIX permissions.
        }
    }

    private void makePrivateFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException exception) {
            // Windows local development has no POSIX permissions.
        }
    }

    private void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            log.warn("Failed to delete temporary report image", cleanupFailure);
        }
    }

    private boolean isValidPng(byte[] content) {
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    return false;
                }
                if (reader.getWidth(0) != REQUIRED_WIDTH
                        || reader.getHeight(0) != REQUIRED_HEIGHT) {
                    return false;
                }
                BufferedImage decoded = reader.read(0);
                return decoded != null
                        && decoded.getWidth() == REQUIRED_WIDTH
                        && decoded.getHeight() == REQUIRED_HEIGHT;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private BusinessException unavailable(Exception cause) {
        log.error("Report image storage is unavailable", cause);
        return new BusinessException(ErrorCode.REPORT_IMAGE_STORAGE_UNAVAILABLE);
    }
}
