package com.feelm.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.feelm.backend.domain.notification.repository.NotificationRepository;
import com.feelm.backend.domain.notification.service.NotificationStreamService;
import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedTestRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@SharedTestRuntime
@SpringBootTest
@AutoConfigureMockMvc
class NotificationStreamHttpTest {
    @Autowired MockMvc mvc;
    @Autowired TokenProvider tokens;
    @Autowired ObjectMapper json;
    @MockitoSpyBean NotificationStreamService streams;
    @MockitoBean NotificationRepository repository;

    @Test
    void authenticationAndActiveAccount() throws Exception {
        mvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/stream").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        when(repository.isActiveUser(anyLong())).thenReturn(false);
        mvc.perform(
                        get("/api/v1/notifications/stream")
                                .header("Authorization", "Bearer " + tokens.createAccessToken(10)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void streamsAndCompletesAuthenticatedAsyncRequest() throws Exception {
        when(repository.isActiveUser(anyLong())).thenReturn(true);
        when(repository.streamVersion(anyLong(), any())).thenReturn("v1");
        AtomicReference<SseEmitter> emitter = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            SseEmitter value = (SseEmitter) invocation.callRealMethod();
                            emitter.set(value);
                            return value;
                        })
                .when(streams)
                .subscribe(anyLong(), any());
        var result =
                mvc.perform(
                                get("/api/v1/notifications/stream")
                                        .header(
                                                "Authorization",
                                                "Bearer " + tokens.createAccessToken(10)))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Accel-Buffering", "no"))
                        .andReturn();
        try {
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () ->
                                    assertThat(result.getResponse().getContentAsString())
                                            .contains("event:notification-changed", "data:{}"));
            streams.reconcile();
            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(
                            () ->
                                    assertThat(result.getResponse().getContentAsString())
                                            .contains(":heartbeat"));
        } finally {
            emitter.get().complete();
        }
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test
    void expiredStreamDoesNotWriteJsonError() throws Exception {
        when(repository.isActiveUser(anyLong())).thenReturn(true);
        when(repository.streamVersion(anyLong(), any())).thenReturn("v1");
        AtomicReference<SseEmitter> emitter = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            SseEmitter value = (SseEmitter) invocation.callRealMethod();
                            emitter.set(value);
                            return value;
                        })
                .when(streams)
                .subscribe(anyLong(), any());
        var result =
                mvc.perform(
                                get("/api/v1/notifications/stream")
                                        .header(
                                                "Authorization",
                                                "Bearer " + tokens.createAccessToken(10)))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () ->
                                assertThat(result.getResponse().getContentAsString())
                                        .contains("event:notification-changed"));
        assertThat(result.getRequest().getRequestURI()).isEqualTo("/api/v1/notifications/stream");
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        emitter.get().completeWithError(new AsyncRequestTimeoutException());
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(result.getResponse().getContentAsString())
                .contains("event:notification-changed")
                .doesNotContain("INTERNAL_ERROR");
    }

    @Test
    void openApiStreamContract() throws Exception {
        String document =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var operation =
                json.readTree(document)
                        .path("paths")
                        .path("/api/v1/notifications/stream")
                        .path("get");
        assertThat(operation.path("security").toString()).contains("bearerAuth");
        assertThat(operation.path("responses").path("200").path("content").has("text/event-stream"))
                .isTrue();
        if (!System.getenv().getOrDefault("FEELM_OPENAPI_SNAPSHOT_WRITE", "").isBlank()) {
            Files.writeString(Path.of("..", "plans", "openapi-sse.json"), document);
        }
    }
}
