package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.support.SharedTestRuntime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 어노테이션 존재가 아니라 실제 springdoc JSON으로 API-070~074 계약을 고정한다. */
@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@SharedTestRuntime
class NotificationOpenApiContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void documentsNotificationEndpointsAndSchemas() throws Exception {
        JsonNode document = document();
        JsonNode paths = document.path("paths");

        JsonNode list = paths.path("/api/v1/notifications").path("get");
        assertBearer(list);
        assertThat(list.path("responses").has("200")).isTrue();
        Map<String, JsonNode> parameters =
                StreamSupport.stream(list.path("parameters").spliterator(), false)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        parameter -> parameter.path("name").asText(),
                                        parameter -> parameter));
        assertThat(parameters.keySet()).containsExactlyInAnyOrder("cursor", "size");
        JsonNode size = parameters.get("size").path("schema");
        assertThat(size.path("minimum").asInt()).isEqualTo(1);
        assertThat(size.path("maximum").asInt()).isEqualTo(50);
        assertThat(size.path("default").asInt()).isEqualTo(20);
        JsonNode item = schema(document, "NotificationItemResponse");
        assertThat(item.path("properties").properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "notificationId",
                        "type",
                        "title",
                        "body",
                        "targetType",
                        "targetId",
                        "readAt",
                        "notifiedAt");
        assertThat(values(item.path("properties").path("type").path("enum")))
                .containsExactlyInAnyOrder("PARTY_INVITE", "WATCH_CONFIRM");

        JsonNode count = paths.path("/api/v1/notifications/unread-count").path("get");
        assertBearer(count);
        assertThat(count.path("responses").has("200")).isTrue();

        JsonNode read = paths.path("/api/v1/notifications/{notificationId}/read").path("patch");
        assertBearer(read);
        assertThat(read.path("responses").has("204")).isTrue();

        JsonNode readAll = paths.path("/api/v1/notifications/read-all").path("patch");
        assertBearer(readAll);
        assertThat(readAll.path("responses").has("204")).isTrue();

        JsonNode dismiss = paths.path("/api/v1/notifications/{notificationId}").path("delete");
        assertBearer(dismiss);
        assertThat(dismiss.path("responses").has("204")).isTrue();
    }

    private void assertBearer(JsonNode operation) throws Exception {
        assertThat(operation.path("security")).isEqualTo(json.readTree("[{\"bearerAuth\":[]}]"));
    }

    private JsonNode schema(JsonNode document, String name) {
        return document.path("components").path("schemas").path(name);
    }

    private List<String> values(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private JsonNode document() throws Exception {
        String body =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith("application/json"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }
}
