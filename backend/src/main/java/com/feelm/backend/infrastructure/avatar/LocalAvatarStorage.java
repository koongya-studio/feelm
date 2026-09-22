package com.feelm.backend.infrastructure.avatar;

import com.feelm.backend.domain.user.service.AvatarStorage;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class LocalAvatarStorage implements AvatarStorage {

    private static final Pattern STORAGE_KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");

    private final AvatarStorageProperties properties;
    private final AvatarImageProcessor processor;

    @PostConstruct
    void prepare() {
        try {
            Files.createDirectories(properties.storagePath());
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public String store(byte[] source) {
        byte[] normalized = processor.normalize(source);
        String storageKey = UUID.randomUUID() + ".jpg";
        Path temporary = null;
        try {
            temporary = Files.createTempFile(properties.storagePath(), ".avatar-", ".tmp");
            Files.write(temporary, normalized);
            makePubliclyReadable(temporary);
            Path target = resolve(storageKey);
            move(temporary, target);
            return storageKey;
        } catch (IOException exception) {
            deleteTemporary(temporary);
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
            log.error("Failed to delete avatar object key={}", storageKey, exception);
        }
    }

    @Override
    public String publicUrl(String storageKey) {
        return storageKey == null ? null : properties.publicUrlPrefix() + "/" + storageKey;
    }

    private Path resolve(String storageKey) {
        if (!STORAGE_KEY.matcher(storageKey).matches()) {
            throw new BusinessException(ErrorCode.AVATAR_STORAGE_UNAVAILABLE);
        }
        Path resolved = properties.storagePath().resolve(storageKey).normalize();
        if (!resolved.getParent().equals(properties.storagePath().normalize())) {
            throw new BusinessException(ErrorCode.AVATAR_STORAGE_UNAVAILABLE);
        }
        return resolved;
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void makePubliclyReadable(Path target) throws IOException {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException exception) {
            // Windows local development has no POSIX permissions; the file is still readable there.
        }
    }

    private void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            log.warn("Failed to delete temporary avatar file", cleanupFailure);
        }
    }

    private BusinessException unavailable(Exception cause) {
        log.error("Avatar storage is unavailable", cause);
        return new BusinessException(ErrorCode.AVATAR_STORAGE_UNAVAILABLE);
    }
}
