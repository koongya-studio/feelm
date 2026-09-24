package com.feelm.backend.infrastructure.gemini;

import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonPrompt;
import com.feelm.backend.domain.recommendation.model.GbtRecommendationReasonResult;
import com.feelm.backend.domain.recommendation.repository.RecommendationReasonGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final boolean gbtAiEnabled;
    private final MeterRegistry meterRegistry;

    @Autowired
    public GeminiRecommendationReasonGenerator(
            ObjectMapper objectMapper,
            @Value("${feelm.recommendation.reason.gemini.api-key:}") String apiKey,
            @Value("${feelm.recommendation.reason.gemini.model:gemini-2.5-flash-lite}")
                    String model,
            @Value("${feelm.recommendation.reason.gbt-ai.enabled:false}") boolean gbtAiEnabled,
            MeterRegistry meterRegistry) {
        this(
                objectMapper,
                apiKey,
                endpointFor(model),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                gbtAiEnabled,
                meterRegistry);
    }

    GeminiRecommendationReasonGenerator(
            ObjectMapper objectMapper,
            String apiKey,
            URI endpoint,
            HttpClient client,
            boolean gbtAiEnabled,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.client = client;
        this.gbtAiEnabled = gbtAiEnabled;
        this.meterRegistry = meterRegistry;
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
    public Map<Long, GbtRecommendationReasonResult> generateGbtReasons(
            List<GbtRecommendationReasonPrompt> prompts) {
        if (!gbtAiEnabled || apiKey.isBlank() || prompts.isEmpty()) {
            return Map.of();
        }
        ObjectNode requestBody = objectMapper.createObjectNode();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("explanationVersion", "gbt-feature-attribution-v1");
        var items = input.putArray("items");
        for (GbtRecommendationReasonPrompt prompt : prompts) {
            ObjectNode item = items.addObject();
            item.put("itemId", prompt.itemId());
            item.set("evidence", objectMapper.valueToTree(prompt.evidence()));
            var allowed = item.putArray("allowedResponses");
            for (AllowedResponse response : allowedResponses(prompt)) {
                ObjectNode option = allowed.addObject();
                option.put("summary", response.summary());
                option.set("usedEvidenceIds", objectMapper.valueToTree(response.evidenceIds()));
            }
        }
        String instruction =
                """
                입력 item마다 allowedResponses 중 하나를 선택하세요.
                summary를 바꾸거나 사실·영화·장르·수치·유사도·감독·배우를 추가하지 마세요.
                응답은 {"reasons":[{"itemId":1,"summary":"...","usedEvidenceIds":["..."]}]}
                JSON 객체만 반환하세요. explanationVersion과 evidence 밖의 ID를 만들지 마세요.
                입력:
                """
                        + input;
        requestBody
                .putArray("contents")
                .addObject()
                .putArray("parts")
                .addObject()
                .put("text", instruction);
        ObjectNode generation = requestBody.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        generation.set("responseSchema", responseSchema());
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(TIMEOUT)
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .build();
        Timer.Sample latency = Timer.start(meterRegistry);
        String stage = "send";
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("GMS GBT reason request returned HTTP {}", response.statusCode());
                return Map.of();
            }
            stage = "outer-json";
            JsonNode payload = objectMapper.readTree(response.body());
            recordUsage(payload.path("usageMetadata"));
            stage = "candidate-text";
            JsonNode content =
                    payload.path("candidates").path(0).path("content").path("parts").path(0);
            if (!content.path("text").isTextual()) {
                return Map.of();
            }
            stage = "reason-json";
            JsonNode reasons =
                    objectMapper.readTree(content.path("text").textValue()).path("reasons");
            return validateGbtResponse(prompts, reasons);
        } catch (IOException | JacksonException exception) {
            log.warn(
                    "GMS GBT reason request or response failed: {}",
                    stage + ":" + exception.getClass().getSimpleName());
            return Map.of();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("GMS GBT reason request was interrupted");
            return Map.of();
        } finally {
            latency.stop(meterRegistry.timer("feelm.recommendation.reason.gbt.ai.latency"));
        }
    }

    private Map<Long, GbtRecommendationReasonResult> validateGbtResponse(
            List<GbtRecommendationReasonPrompt> prompts, JsonNode reasons) {
        if (!reasons.isArray()) {
            validationFailures(prompts.size());
            return Map.of();
        }
        Map<Long, GbtRecommendationReasonPrompt> expected = new HashMap<>();
        prompts.forEach(prompt -> expected.put(prompt.itemId(), prompt));
        Map<Long, GbtRecommendationReasonResult> result = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        for (JsonNode value : reasons) {
            JsonNode itemIdNode = value.path("itemId");
            if (!itemIdNode.isIntegralNumber() || !itemIdNode.canConvertToLong()) {
                continue;
            }
            long itemId = itemIdNode.longValue();
            GbtRecommendationReasonPrompt prompt = expected.get(itemId);
            if (!value.isObject()
                    || prompt == null
                    || !seen.add(itemId)
                    || !value.path("summary").isTextual()
                    || !value.path("usedEvidenceIds").isArray()) {
                continue;
            }
            List<String> ids = new java.util.ArrayList<>();
            for (JsonNode id : value.path("usedEvidenceIds")) {
                if (!id.isTextual()) {
                    ids.clear();
                    break;
                }
                ids.add(id.textValue());
            }
            GbtRecommendationReasonResult generated =
                    new GbtRecommendationReasonResult(value.path("summary").textValue(), ids);
            if (!prompt.supports(generated)) {
                continue;
            }
            result.put(itemId, generated);
        }
        validationFailures(expected.size() - result.size());
        return Map.copyOf(result);
    }

    private List<AllowedResponse> allowedResponses(GbtRecommendationReasonPrompt prompt) {
        List<AllowedResponse> result = new java.util.ArrayList<>();
        int combinations = 1 << prompt.evidence().size();
        for (int mask = 1; mask < combinations; mask++) {
            List<String> ids = new java.util.ArrayList<>();
            for (int index = 0; index < prompt.evidence().size(); index++) {
                if ((mask & (1 << index)) != 0) {
                    ids.add(prompt.evidence().get(index).evidenceId());
                }
            }
            for (String summary : prompt.allowedSummaries(ids)) {
                result.add(new AllowedResponse(summary, List.copyOf(ids)));
            }
        }
        return List.copyOf(result);
    }

    private JsonNode responseSchema() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", "OBJECT");
        ObjectNode reasons = result.putObject("properties").putObject("reasons");
        reasons.put("type", "ARRAY");
        ObjectNode value = reasons.putObject("items");
        value.put("type", "OBJECT");
        ObjectNode properties = value.putObject("properties");
        properties.putObject("itemId").put("type", "INTEGER");
        properties.putObject("summary").put("type", "STRING");
        properties
                .putObject("usedEvidenceIds")
                .put("type", "ARRAY")
                .putObject("items")
                .put("type", "STRING");
        value.putArray("required").add("itemId").add("summary").add("usedEvidenceIds");
        result.putArray("required").add("reasons");
        return result;
    }

    private void recordUsage(JsonNode usage) {
        recordToken(usage, "promptTokenCount", "prompt");
        recordToken(usage, "candidatesTokenCount", "candidate");
    }

    private void recordToken(JsonNode usage, String field, String kind) {
        JsonNode value = usage.path(field);
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0) {
            meterRegistry
                    .counter("feelm.recommendation.reason.gbt.ai.tokens", "kind", kind)
                    .increment(value.longValue());
        }
    }

    private void validationFailures(int count) {
        if (count <= 0) {
            return;
        }
        meterRegistry
                .counter("feelm.recommendation.reason.gbt.ai", "outcome", "validation_failure")
                .increment(count);
    }

    private record AllowedResponse(String summary, List<String> evidenceIds) {}
}
