package com.feelm.backend.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.model.RecommendationReasonPrompt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class GeminiRecommendationReasonGeneratorTest {

    private final HttpClient client = mock(HttpClient.class);
    private GeminiRecommendationReasonGenerator generator;
    private final List<RecommendationReasonPrompt> prompts =
            List.of(new RecommendationReasonPrompt(1, "SHARED_GENRE", "영화", "스릴러", null));

    @BeforeEach
    void setUp() {
        generator =
                new GeminiRecommendationReasonGenerator(
                        new ObjectMapper(),
                        "test-key",
                        URI.create("https://example.test/generateContent"),
                        client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsStructuredVariantResponse() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        """
                        {"candidates":[{"content":{"parts":[{"text":
                          "{\\"variants\\":{\\"1\\":\\"SOURCE_GENRE\\"}}"}]}}]}
                        """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThat(generator.selectVariants(prompts)).isEqualTo(Map.of(1L, "SOURCE_GENRE"));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(client)
                .send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().firstValue("x-goog-api-key")).contains("test-key");
        assertThat(request.getValue().method()).isEqualTo("POST");
    }

    @Test
    void usesGmsGatewayEndpoint() {
        assertThat(GeminiRecommendationReasonGenerator.endpointFor("gemini-2.5-flash-lite"))
                .isEqualTo(
                        URI.create(
                                "https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiErrorAndTimeoutReturnNoSelections() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response)
                .thenThrow(new HttpTimeoutException("timed out"));

        assertThat(generator.selectVariants(prompts)).isEmpty();
        assertThat(generator.selectVariants(prompts)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedStructuredResponseReturnsNoSelections() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThat(generator.selectVariants(prompts)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyResponseTextReturnsNoSelections() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThat(generator.selectVariants(prompts)).isEmpty();
    }

    @Test
    void missingKeySkipsExternalCall() {
        GeminiRecommendationReasonGenerator disabled =
                new GeminiRecommendationReasonGenerator(
                        new ObjectMapper(),
                        "",
                        URI.create("https://example.test/generateContent"),
                        client);

        assertThat(disabled.selectVariants(prompts)).isEmpty();
        verifyNoInteractions(client);
    }
}
