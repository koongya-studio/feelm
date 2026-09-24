package com.feelm.backend.infrastructure.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonPrompt;
import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonPrompt.Evidence;
import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class GeminiRecommendationReasonGeneratorTest {

    private final HttpClient client = mock(HttpClient.class);
    private GeminiRecommendationReasonGenerator generator;

    @BeforeEach
    void setUp() {
        generator = generator(new SimpleMeterRegistry());
    }

    @Test
    void usesGmsGatewayEndpoint() {
        assertThat(GeminiRecommendationReasonGenerator.endpointFor("gemini-2.5-flash-lite"))
                .isEqualTo(
                        URI.create(
                                "https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent"));
    }

    @Test
    void missingKeySkipsExternalCall() {
        GeminiRecommendationReasonGenerator disabled =
                new GeminiRecommendationReasonGenerator(
                        new ObjectMapper(),
                        "",
                        URI.create("https://example.test/generateContent"),
                        client,
                        true,
                        new SimpleMeterRegistry());

        assertThat(disabled.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsGroundedSummaryAndEvidenceIds() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GeminiRecommendationReasonGenerator grounded = generator(metrics);
        HttpResponse<String> response = response(successBody(1, "a"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThat(grounded.generateGbtReasons(List.of(gbtPrompt())))
                .containsEntry(
                        1L,
                        new GbtRecommendationReasonResult(
                                "장르 취향 패턴을 바탕으로 골랐어요.", List.of("sha256:" + "a".repeat(64))));
        assertThat(
                        metrics.get("feelm.recommendation.reason.gbt.ai.tokens")
                                .tag("kind", "prompt")
                                .counter()
                                .count())
                .isEqualTo(10);
        assertThat(
                        metrics.get("feelm.recommendation.reason.gbt.ai.tokens")
                                .tag("kind", "candidate")
                                .counter()
                                .count())
                .isEqualTo(4);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(client)
                .send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://example.test/generateContent");
        assertThat(request.getValue().headers().firstValue("x-goog-api-key")).contains("test-key");
        assertThat(body(request.getValue().bodyPublisher().orElseThrow()))
                .contains(
                        "gbt-feature-attribution-v1",
                        "responseSchema",
                        "allowedResponses",
                        "sha256:" + "a".repeat(64));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsVerifiedSpecificSentenceAndRejectsInventedMovie() throws Exception {
        String id = "sha256:" + "a".repeat(64);
        GbtRecommendationReasonPrompt prompt =
                new GbtRecommendationReasonPrompt(
                        1,
                        "gbt-feature-attribution-v2",
                        List.of(
                                new Evidence(
                                        id,
                                        "CONTENT_RELATION",
                                        "선호 콘텐츠와의 연관성",
                                        "\"스파이더맨\"을 재미있게 봐서 골랐어요.")));
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        outerBody(
                                "{\"reasons\":[{\"itemId\":1,\"summary\":\"\\\"스파이더맨\\\"을 재미있게 봐서 골랐어요.\",\"usedEvidenceIds\":[\""
                                        + id
                                        + "\"]}]}"))
                .thenReturn(
                        outerBody(
                                "{\"reasons\":[{\"itemId\":1,\"summary\":\"\\\"배트맨\\\"을 재미있게 봐서 골랐어요.\",\"usedEvidenceIds\":[\""
                                        + id
                                        + "\"]}]}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThat(generator.generateGbtReasons(List.of(prompt)))
                .containsEntry(
                        1L,
                        new GbtRecommendationReasonResult("\"스파이더맨\"을 재미있게 봐서 골랐어요.", List.of(id)));
        assertThat(generator.generateGbtReasons(List.of(prompt))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnknownEvidenceAndUnsupportedClaims() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GeminiRecommendationReasonGenerator grounded = generator(metrics);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        outerBody(
                                "{\"reasons\":[{\"itemId\":1,\"summary\":\"액션 영화를 좋아해서 골랐어요.\",\"usedEvidenceIds\":[\"sha256:"
                                        + "a".repeat(64)
                                        + "\"]}]}"))
                .thenReturn(
                        outerBody(
                                "{\"reasons\":[{\"itemId\":1,\"summary\":\"장르 취향 패턴을 바탕으로 골랐어요.\",\"usedEvidenceIds\":[\"sha256:"
                                        + "b".repeat(64)
                                        + "\"]}]}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThat(grounded.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        assertThat(grounded.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        assertThat(
                        metrics.get("feelm.recommendation.reason.gbt.ai")
                                .tag("outcome", "validation_failure")
                                .counter()
                                .count())
                .isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsEmptyAndDuplicateEvidenceIds() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GeminiRecommendationReasonGenerator grounded = generator(metrics);
        String firstId = "sha256:" + "a".repeat(64);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        outerBody(
                                "{\"reasons\":[{\"itemId\":1,\"summary\":\"장르 취향 패턴을 바탕으로 골랐어요.\",\"usedEvidenceIds\":[]}]}"))
                .thenReturn(
                        outerBody(
                                "{\"reasons\":[{\"itemId\":1,\"summary\":\"장르 취향 패턴·평가 성향을 바탕으로 골랐어요.\",\"usedEvidenceIds\":[\""
                                        + firstId
                                        + "\",\""
                                        + firstId
                                        + "\"]}]}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        GbtRecommendationReasonPrompt prompt =
                new GbtRecommendationReasonPrompt(
                        1,
                        "gbt-feature-attribution-v1",
                        List.of(
                                new Evidence(firstId, "GENRE_PATTERN", "장르 취향 패턴"),
                                new Evidence(
                                        "sha256:" + "b".repeat(64), "RATING_PATTERN", "평가 성향")));

        assertThat(grounded.generateGbtReasons(List.of(prompt))).isEmpty();
        assertThat(grounded.generateGbtReasons(List.of(prompt))).isEmpty();
        assertThat(
                        metrics.get("feelm.recommendation.reason.gbt.ai")
                                .tag("outcome", "validation_failure")
                                .counter()
                                .count())
                .isEqualTo(2);
    }

    @Test
    void disabledGbtFlagSkipsGroundedExternalCall() {
        GeminiRecommendationReasonGenerator disabled =
                new GeminiRecommendationReasonGenerator(
                        new ObjectMapper(),
                        "test-key",
                        URI.create("https://example.test/generateContent"),
                        client,
                        false,
                        new SimpleMeterRegistry());

        assertThat(disabled.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void gbtHttpTimeoutAndMalformedJsonReturnNoReasons() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GeminiRecommendationReasonGenerator measured = generator(metrics);
        HttpResponse<String> unavailable = mock(HttpResponse.class);
        when(unavailable.statusCode()).thenReturn(503);
        HttpResponse<String> malformed = response("not-json");
        HttpResponse<String> empty = response(outerBody("{}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(unavailable)
                .thenThrow(new HttpTimeoutException("timed out"))
                .thenReturn(malformed)
                .thenReturn(empty);

        assertThat(measured.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        assertThat(measured.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        assertThat(measured.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        assertThat(measured.generateGbtReasons(List.of(gbtPrompt()))).isEmpty();
        assertThat(metrics.get("feelm.recommendation.reason.gbt.ai.latency").timer().count())
                .isEqualTo(4);
        assertThat(
                        metrics.get("feelm.recommendation.reason.gbt.ai")
                                .tag("outcome", "validation_failure")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsMissingGbtItemsAsValidationFailures() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GeminiRecommendationReasonGenerator measured = generator(metrics);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(successBody(1, "a"))
                .thenReturn(outerBody("{\"reasons\":[]}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        List<GbtRecommendationReasonPrompt> prompts = List.of(gbtPrompt(), gbtPrompt(2, "b"));

        assertThat(measured.generateGbtReasons(prompts)).containsOnlyKeys(1L);
        assertThat(measured.generateGbtReasons(prompts)).isEmpty();
        assertThat(
                        metrics.get("feelm.recommendation.reason.gbt.ai")
                                .tag("outcome", "validation_failure")
                                .counter()
                                .count())
                .isEqualTo(3);
    }

    private GeminiRecommendationReasonGenerator generator(SimpleMeterRegistry metrics) {
        return new GeminiRecommendationReasonGenerator(
                new ObjectMapper(),
                "test-key",
                URI.create("https://example.test/generateContent"),
                client,
                true,
                metrics);
    }

    private GbtRecommendationReasonPrompt gbtPrompt() {
        return gbtPrompt(1, "a");
    }

    private GbtRecommendationReasonPrompt gbtPrompt(long itemId, String digestCharacter) {
        return new GbtRecommendationReasonPrompt(
                itemId,
                "gbt-feature-attribution-v1",
                List.of(
                        new Evidence(
                                "sha256:" + digestCharacter.repeat(64),
                                "GENRE_PATTERN",
                                "장르 취향 패턴")));
    }

    private HttpResponse<String> response(String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private String successBody(long itemId, String digestCharacter) {
        var root = new ObjectMapper().createObjectNode();
        root.putObject("usageMetadata").put("promptTokenCount", 10).put("candidatesTokenCount", 4);
        root.putArray("candidates")
                .addObject()
                .putObject("content")
                .putArray("parts")
                .addObject()
                .put(
                        "text",
                        """
                        {"reasons":[{"itemId":%d,"summary":"장르 취향 패턴을 바탕으로 골랐어요.","usedEvidenceIds":["sha256:%s"]}]}
                        """
                                .formatted(itemId, digestCharacter.repeat(64)));
        return root.toString();
    }

    private String outerBody(String text) {
        var root = new ObjectMapper().createObjectNode();
        root.putArray("candidates")
                .addObject()
                .putObject("content")
                .putArray("parts")
                .addObject()
                .put("text", text);
        return root.toString();
    }

    private String body(BodyPublisher publisher) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> completed = new CompletableFuture<>();
        publisher.subscribe(
                new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                        byte[] bytes = new byte[item.remaining()];
                        item.get(bytes);
                        output.writeBytes(bytes);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        completed.completeExceptionally(throwable);
                    }

                    @Override
                    public void onComplete() {
                        completed.complete(null);
                    }
                });
        completed.join();
        return output.toString(StandardCharsets.UTF_8);
    }
}
