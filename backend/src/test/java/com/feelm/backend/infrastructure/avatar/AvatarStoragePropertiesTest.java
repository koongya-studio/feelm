package com.feelm.backend.infrastructure.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarStoragePropertiesTest {

    @Test
    void normalizesAbsolutePathAndTrailingSlash() {
        AvatarStorageProperties properties =
                new AvatarStorageProperties(
                        Path.of(System.getProperty("java.io.tmpdir"), "avatars"),
                        "/media/avatars/");

        assertThat(properties.storagePath()).isAbsolute();
        assertThat(properties.publicUrlPrefix()).isEqualTo("/media/avatars");
    }

    @Test
    void rejectsMissingBlankAndRelativeStoragePath() {
        assertThatThrownBy(() -> new AvatarStorageProperties(null, "/media/avatars"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvatarStorageProperties(Path.of(""), "/media/avatars"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new AvatarStorageProperties(
                                        Path.of("build/avatars"), "/media/avatars"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafePublicUrlPrefix() {
        Path path = Path.of("build/avatars");

        assertThatThrownBy(() -> new AvatarStorageProperties(path, "//example.com/avatar"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvatarStorageProperties(path, "/media/../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvatarStorageProperties(path, "/"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
