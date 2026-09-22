package com.feelm.backend.infrastructure.avatar;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "feelm.avatar")
public record AvatarStorageProperties(Path storagePath, String publicUrlPrefix) {

    public AvatarStorageProperties {
        if (storagePath == null || storagePath.toString().isBlank() || !storagePath.isAbsolute()) {
            throw new IllegalArgumentException("avatar storage-path must be an absolute path");
        }
        storagePath = storagePath.normalize();
        if (publicUrlPrefix == null
                || publicUrlPrefix.isBlank()
                || !publicUrlPrefix.startsWith("/")
                || publicUrlPrefix.startsWith("//")
                || publicUrlPrefix.contains("..")
                || publicUrlPrefix.contains("\\")
                || publicUrlPrefix.contains("?")
                || publicUrlPrefix.contains("#")) {
            throw new IllegalArgumentException("avatar public-url-prefix must be a safe path");
        }
        publicUrlPrefix = publicUrlPrefix.replaceAll("/+$", "");
        if (publicUrlPrefix.isEmpty()) {
            throw new IllegalArgumentException("avatar public-url-prefix must not be empty");
        }
    }
}
