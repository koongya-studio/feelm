package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.support.SharedTestRuntime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
@SharedTestRuntime
class OttRecommendationOpenApiContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void documentsEndpoint() throws Exception {
        String body =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode document = mapper.readTree(body);
        JsonNode operation =
                document.path("paths").path("/api/v1/recommendations/otts").path("get");
        assertThat(operation.isObject()).isTrue();
        assertThat(operation.path("responses").path("200").isObject()).isTrue();
        assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(
                        operation.path("parameters").isMissingNode()
                                || operation.path("parameters").isEmpty())
                .isTrue();
        JsonNode response =
                document.path("components")
                        .path("schemas")
                        .path("OttRecommendationResponse")
                        .path("properties");
        assertThat(response.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("insufficientData", "aggregatedAt", "items");
    }
}
