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

/** 어노테이션 존재가 아니라 실제 springdoc JSON으로 알림 API 계약을 고정한다. */
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
                .containsExactlyInAnyOrder(
                        "PARTY_INVITE", "PARTY_INVITE_ACCEPTED", "WATCH_CONFIRM");

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

    @Test
    void documentsWebPushApisAndValidation() throws Exception {
        JsonNode document = document();
        JsonNode paths = document.path("paths");
        JsonNode config = paths.path("/api/v1/notifications/push/config").path("get");
        JsonNode register = paths.path("/api/v1/notifications/push/subscriptions").path("put");
        JsonNode unregister =
                paths.path("/api/v1/notifications/push/subscriptions/{subscriptionId}")
                        .path("delete");

        assertThat(config.isMissingNode()).isFalse();
        assertThat(register.isMissingNode()).isFalse();
        assertThat(unregister.isMissingNode()).isFalse();
        assertBearer(config);
        assertBearer(register);
        assertBearer(unregister);
        assertThat(config.path("responses").propertyNames()).contains("200", "401");
        assertThat(register.path("responses").propertyNames()).contains("200", "400", "401", "503");
        assertThat(unregister.path("responses").propertyNames()).contains("204", "401", "404");
        JsonNode configResponse = schema(document, "WebPushConfigResponse");
        assertThat(configResponse.path("properties").propertyNames())
                .containsExactlyInAnyOrder("enabled", "publicKey");
        JsonNode subscriptionResponse = schema(document, "WebPushSubscriptionResponse");
        assertThat(subscriptionResponse.path("properties").propertyNames())
                .containsExactly("subscriptionId");
        assertThat(schema(document, "ErrorResponse").path("properties").propertyNames())
                .contains("code", "message");
        JsonNode requestSchema = schema(document, "WebPushSubscriptionRequest");
        assertThat(requestSchema.path("properties").propertyNames())
                .contains("endpoint", "expirationTime", "keys");
        assertThat(requestSchema.path("required").toString()).contains("endpoint", "keys");
        assertThat(requestSchema.path("properties").path("endpoint").path("maxLength").asInt())
                .isEqualTo(2048);
        assertThat(requestSchema.path("properties").path("expirationTime").path("minimum").asLong())
                .isEqualTo(1L);
        JsonNode keys = referencedSchema(document, requestSchema.path("properties").path("keys"));
        assertThat(keys.path("required").toString()).contains("p256dh", "auth");
        assertThat(keys.path("properties").path("p256dh").path("maxLength").asInt()).isEqualTo(256);
        assertThat(keys.path("properties").path("auth").path("maxLength").asInt()).isEqualTo(128);
    }

    private void assertBearer(JsonNode operation) throws Exception {
        assertThat(operation.path("security")).isEqualTo(json.readTree("[{\"bearerAuth\":[]}]"));
    }

    private JsonNode schema(JsonNode document, String name) {
        return document.path("components").path("schemas").path(name);
    }

    private JsonNode referencedSchema(JsonNode document, JsonNode reference) {
        String path = reference.path("$ref").asText();
        return schema(document, path.substring(path.lastIndexOf('/') + 1));
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
