package com.feelm.backend.infrastructure.gemini;

import com.feelm.backend.domain.recommendation.model.RecommendationReasonPrompt;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonGenerator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Gemini는 확인된 근거의 표현 방식을 고르고, 최종 문장은 Spring이 안전하게 조립한다. */
@Component
public class GeminiRecommendationReasonGenerator implements RecommendationReasonGenerator {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiRecommendationReasonGenerator.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final URI endpoint;
    private final HttpClient client;

    @Autowired
    public GeminiRecommendationReasonGenerator(
            ObjectMapper objectMapper,
            @Value("${feelm.recommendation.reason.gemini.api-key:}") String apiKey,
            @Value("${feelm.recommendation.reason.gemini.model:gemini-2.5-flash-lite}")
                    String model) {
        this(
                objectMapper,
                apiKey,
                endpointFor(model),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    GeminiRecommendationReasonGenerator(
            ObjectMapper objectMapper, String apiKey, URI endpoint, HttpClient client) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.client = client;
    }

    static URI endpointFor(String model) {
        if (!model.matches("[a-zA-Z0-9.-]+")) {
            throw new IllegalArgumentException("Invalid Gemini model name");
        }
        return URI.create(
                "https://gms.ssafy.io/gmsapi/generativelanguage.googleapis.com/v1beta/models/"
                        + model
                        + ":generateContent");
    }

    @Override
    public Map<Long, String> selectVariants(List<RecommendationReasonPrompt> prompts) {
        if (apiKey.isBlank() || prompts.isEmpty()) {
            return Map.of();
        }
        ObjectNode requestBody = objectMapper.createObjectNode();
        String instruction =
                """
                추천 근거마다 허용된 표현 유형만 선택하세요. 사실이나 문장을 생성하지 마세요.
                응답은 {"variants":{"itemId":"variant"}} JSON 객체만 반환하세요.
                SHARED_GENRE: GENRE_ONLY.
                TOP_RATED_SOURCE: SOURCE 또는 HISTORY.
                DISCOVERY_PERSONAL_SCORE: GENRE.
                DISCOVERY_AGGREGATE_AFFINITY: HISTORY.
                입력에 없는 itemId나 유형을 만들지 마세요.
                입력:
                """
                        + objectMapper.valueToTree(prompts);
        requestBody
                .putArray("contents")
                .addObject()
                .putArray("parts")
                .addObject()
                .put("text", instruction);
        requestBody.putObject("generationConfig").put("responseMimeType", "application/json");
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(TIMEOUT)
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build();
        String stage = "send";
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn(
                        "GMS recommendation reason request returned HTTP {}",
                        response.statusCode());
                return Map.of();
            }
            stage = "outer-json";
            JsonNode payload = objectMapper.readTree(response.body());
            stage = "candidate-text";
            JsonNode content =
                    payload.path("candidates").path(0).path("content").path("parts").path(0);
            if (!content.path("text").isTextual()) {
                return Map.of();
            }
            stage = "variant-json";
            JsonNode variants =
                    objectMapper.readTree(content.path("text").textValue()).path("variants");
            if (!variants.isObject()) {
                return Map.of();
            }
            Map<Long, String> selected = new HashMap<>();
            for (RecommendationReasonPrompt prompt : prompts) {
                String variant = variants.path(Long.toString(prompt.itemId())).textValue();
                if (variant != null) {
                    selected.put(prompt.itemId(), variant);
                }
            }
            return Map.copyOf(selected);
        } catch (IOException | JacksonException exception) {
            log.warn(
                    "GMS recommendation reason request or response failed: {}",
                    stage + ":" + exception.getClass().getSimpleName());
            return Map.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("GMS recommendation reason request was interrupted");
            return Map.of();
        }
    }
}
