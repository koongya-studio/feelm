package com.feelm.backend.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("CursorPageResponse — API-000 페이지네이션 응답 구조")
class CursorPageResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("items, nextCursor, hasNext 키로 직렬화된다")
    void serializesToContractShape() throws Exception {
        CursorPageResponse<String> response =
                new CursorPageResponse<>(List.of("a", "b"), "eyJpZCI6MTIzfQ", true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.has("items")).isTrue();
        assertThat(json.get("items").isArray()).isTrue();
        assertThat(json.get("items")).hasSize(2);
        assertThat(json.get("nextCursor").asText()).isEqualTo("eyJpZCI6MTIzfQ");
        assertThat(json.get("hasNext").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("마지막 페이지는 hasNext false로 표현한다")
    void lastPageHasNextFalse() throws Exception {
        CursorPageResponse<String> response = new CursorPageResponse<>(List.of(), null, false);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.get("items")).isEmpty();
        assertThat(json.get("hasNext").asBoolean()).isFalse();
    }
}
