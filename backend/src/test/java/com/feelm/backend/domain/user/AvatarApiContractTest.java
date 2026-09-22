package com.feelm.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.domain.user.service.AvatarStorage;
import com.feelm.backend.infrastructure.avatar.AvatarStorageProperties;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("프로필 사진 API 계약")
class AvatarApiContractTest extends UserProfileApiTestSupport {

    private static final String AVATAR_PATH = "/api/v1/users/me/avatar";

    @Autowired private AvatarStorage storage;
    @Autowired private AvatarStorageProperties properties;

    @Test
    @DisplayName("PNG를 512 정사각 JPEG로 저장하고 내 프로필에 공개 URL을 반영한다")
    void uploadsNormalizedAvatar() throws Exception {
        long userId = activeUser("avatar@example.com", "사진사용자");

        String body =
                mockMvc.perform(
                                multipart(AVATAR_PATH)
                                        .file(image("file", "avatar.png", "image/png", 800, 400))
                                        .with(
                                                request -> {
                                                    request.setMethod("PUT");
                                                    return request;
                                                })
                                        .header("Authorization", bearerFor(userId)))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.avatarUrl")
                                        .value(org.hamcrest.Matchers.startsWith("/media/avatars/")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String avatarUrl = objectMapper.readTree(body).get("avatarUrl").asText();
        String storageKey = avatarUrl.substring(avatarUrl.lastIndexOf('/') + 1);
        Path stored = properties.storagePath().resolve(storageKey);
        BufferedImage normalized = ImageIO.read(stored.toFile());

        assertThat(Files.exists(stored)).isTrue();
        assertThat(normalized.getWidth()).isEqualTo(512);
        assertThat(normalized.getHeight()).isEqualTo(512);
        assertThat(Files.readAllBytes(stored)).startsWith((byte) 0xff, (byte) 0xd8);
        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(avatarUrl));

        storage.delete(storageKey);
    }

    @Test
    @DisplayName("새 사진으로 교체하면 새 URL을 발급하고 이전 파일을 제거한다")
    void replacementRemovesPreviousFile() throws Exception {
        long userId = activeUser("replace-avatar@example.com", "교체사용자");
        String first = upload(userId, "first.png", 640, 480);
        String firstKey = key(first);

        String second = upload(userId, "second.png", 480, 640);

        assertThat(second).isNotEqualTo(first);
        assertThat(Files.exists(properties.storagePath().resolve(firstKey))).isFalse();
        storage.delete(key(second));
    }

    @Test
    @DisplayName("삭제하면 204를 반환하고 DB URL과 원본 파일을 함께 제거한다")
    void deletesAvatar() throws Exception {
        long userId = activeUser("delete-avatar@example.com", "삭제사용자");
        String avatarUrl = upload(userId, "delete.png", 300, 300);
        Path stored = properties.storagePath().resolve(key(avatarUrl));

        mockMvc.perform(delete(AVATAR_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());

        assertThat(Files.exists(stored)).isFalse();
        mockMvc.perform(get(ME_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        mockMvc.perform(delete(AVATAR_PATH).header("Authorization", bearerFor(userId)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("이미지가 아니거나 비어 있는 파일은 거부한다")
    void rejectsInvalidFile() throws Exception {
        long userId = activeUser("invalid-avatar@example.com", "오류사용자");
        MockMultipartFile file =
                new MockMultipartFile("file", "avatar.png", "image/png", "not-an-image".getBytes());

        mockMvc.perform(
                        multipart(AVATAR_PATH)
                                .file(file)
                                .with(
                                        request -> {
                                            request.setMethod("PUT");
                                            return request;
                                        })
                                .header("Authorization", bearerFor(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AVATAR_INVALID"));
    }

    @Test
    @DisplayName("10 MiB를 넘는 파일은 413으로 거부한다")
    void rejectsOversizedFile() throws Exception {
        long userId = activeUser("large-avatar@example.com", "큰파일사용자");
        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "avatar.png", "image/png", new byte[10 * 1024 * 1024 + 1]);

        mockMvc.perform(
                        multipart(AVATAR_PATH)
                                .file(file)
                                .with(
                                        request -> {
                                            request.setMethod("PUT");
                                            return request;
                                        })
                                .header("Authorization", bearerFor(userId)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("AVATAR_TOO_LARGE"));
    }

    @Test
    @DisplayName("업로드와 삭제에는 인증이 필요하다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(
                        multipart(AVATAR_PATH)
                                .file(image("file", "avatar.jpg", "image/jpeg", 10, 10))
                                .with(
                                        request -> {
                                            request.setMethod("PUT");
                                            return request;
                                        }))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(AVATAR_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴 계정은 이미지 처리 전에 401로 거부한다")
    void rejectsWithdrawnUser() throws Exception {
        long userId =
                insertUser(
                        "withdrawn-avatar@example.com",
                        null,
                        "탈퇴사진",
                        "WITHDRAWN",
                        "PRIVATE",
                        false);

        mockMvc.perform(
                        multipart(AVATAR_PATH)
                                .file(image("file", "avatar.jpg", "image/jpeg", 100, 100))
                                .with(
                                        request -> {
                                            request.setMethod("PUT");
                                            return request;
                                        })
                                .header("Authorization", bearerFor(userId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String upload(long userId, String name, int width, int height) throws Exception {
        String response =
                mockMvc.perform(
                                multipart(AVATAR_PATH)
                                        .file(image("file", name, "image/png", width, height))
                                        .with(
                                                request -> {
                                                    request.setMethod("PUT");
                                                    return request;
                                                })
                                        .header("Authorization", bearerFor(userId)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(response).get("avatarUrl").asText();
    }

    private MockMultipartFile image(
            String partName, String filename, String contentType, int width, int height)
            throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 120, 210, 180));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile(partName, filename, contentType, output.toByteArray());
    }

    private String key(String avatarUrl) {
        return avatarUrl.substring(avatarUrl.lastIndexOf('/') + 1);
    }
}
