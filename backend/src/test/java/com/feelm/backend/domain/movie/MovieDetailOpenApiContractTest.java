package com.feelm.backend.domain.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.global.error.ErrorResponse;
import com.feelm.backend.global.security.SecurityConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 어노테이션이 아닌 실제 springdoc JSON으로 API-030과 주변 API의 보안 계약을 고정한다. */
@SpringBootTest(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "feelm.auth.mock.enabled=false"
        })
@AutoConfigureMockMvc
class MovieDetailOpenApiContractTest {

    private static final String PATH = "/api/v1/movies/{movieId}";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void describesOperationAndParameters() throws Exception {
        JsonNode operation = document().path("paths").path(PATH).path("get");
        assertThat(operation.isObject()).isTrue();
        assertThat(operation.path("summary").asText()).isEqualTo("영화 상세 조회");
        assertThat(operation.path("description").asText())
                .contains("비회원", "myRating", "myWatchStatus", "recommendation");
        List<JsonNode> parameters =
                StreamSupport.stream(operation.path("parameters").spliterator(), false).toList();
        assertThat(parameters)
                .extracting(p -> p.path("name").asText())
                .containsExactlyInAnyOrder("movieId", "recommendationItemId");
        for (JsonNode parameter : parameters) {
            boolean movie = parameter.path("name").asText().equals("movieId");
            assertThat(parameter.path("in").asText()).isEqualTo(movie ? "path" : "query");
            assertThat(parameter.path("required").asBoolean()).isEqualTo(movie);
            assertThat(parameter.path("description").asText()).contains("양의 64비트 정수");
            assertThat(parameter.path("schema").path("type").asText()).isEqualTo("integer");
            assertThat(parameter.path("schema").path("format").asText()).isEqualTo("int64");
            assertThat(parameter.path("schema").path("minimum").asLong()).isEqualTo(1L);
        }
    }

    @Test
    void describesResponsesAndNestedSchemas() throws Exception {
        JsonNode document = document();
        JsonNode responses = document.path("paths").path(PATH).path("get").path("responses");
        assertThat(responses.properties())
                .extracting(Map.Entry::getKey)
                .contains("200", "400", "404", "500");
        for (String code : List.of("200", "400", "404", "500")) {
            JsonNode response = responses.path(code);
            assertThat(response.path("description").asText()).isNotBlank();
            assertThat(
                            response.path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("$ref")
                                    .asText())
                    .isEqualTo(
                            "#/components/schemas/"
                                    + (code.equals("200")
                                            ? "MovieDetailResponse"
                                            : "ErrorResponse"));
        }
        JsonNode schemas = document.path("components").path("schemas");
        JsonNode properties = schemas.path("MovieDetailResponse").path("properties");
        assertThat(properties.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "movieId",
                        "title",
                        "originalTitle",
                        "synopsis",
                        "releaseDate",
                        "runtimeMinutes",
                        "genres",
                        "countries",
                        "directors",
                        "cast",
                        "posterUrl",
                        "backdropUrl",
                        "flavorCode",
                        "averageRating",
                        "ratingCount",
                        "ottProviders",
                        "myRating",
                        "myWatchStatus",
                        "recommendation");
        assertThat(properties.path("cast").path("description").asText()).contains("10");
        assertThat(properties.path("directors").path("description").asText()).contains("전체");
        assertThat(properties.path("ottProviders").path("description").asText())
                .contains("구독", "ottId");
        for (String name :
                List.of(
                        "GenreResponse",
                        "CountryResponse",
                        "DirectorResponse",
                        "CastResponse",
                        "MovieOttProviderResponse",
                        "MyRatingResponse",
                        "MovieRecommendationResponse")) {
            JsonNode schema = schemas.path(name);
            assertThat(schema.path("description").asText()).as(name).isNotBlank();
            assertThat(schema.path("properties").properties())
                    .allSatisfy(
                            property ->
                                    assertThat(property.getValue().path("description").asText())
                                            .as(name + "." + property.getKey())
                                            .isNotBlank());
        }
        assertThat(
                        schemas.path("MovieRecommendationResponse")
                                .path("properties")
                                .path("reason")
                                .path("type")
                                .asText())
                .isEqualTo("object");
    }

    @Test
    void internalErrorUsesCommonCodeAndMessage() throws Exception {
        JsonNode document = document();
        JsonNode response =
                document.path("paths").path(PATH).path("get").path("responses").path("500");
        assertThat(response.isObject()).isTrue();
        assertThat(response.path("description").asText()).contains("INTERNAL_ERROR");
        JsonNode media = response.path("content").path("application/json");
        assertThat(media.path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ErrorResponse");
        JsonNode properties =
                document.path("components")
                        .path("schemas")
                        .path("ErrorResponse")
                        .path("properties");
        assertThat(properties.path("code").path("type").asText()).isEqualTo("string");
        assertThat(properties.path("message").path("type").asText()).isEqualTo("string");
        JsonNode example = media.path("example");
        assertThat(example)
                .isEqualTo(mapper.valueToTree(ErrorResponse.from(ErrorCode.INTERNAL_ERROR)));
        assertThat(example.properties())
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("code", "message");
    }

    @Test
    void responseKeysAreRequiredEvenWhenTheirValuesAreNullable() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        for (String name :
                List.of(
                        "MovieDetailResponse",
                        "GenreResponse",
                        "CountryResponse",
                        "DirectorResponse",
                        "CastResponse",
                        "MovieOttProviderResponse",
                        "MyRatingResponse",
                        "MovieRecommendationResponse")) {
            JsonNode schema = schemas.path(name);
            assertThat(
                            StreamSupport.stream(schema.path("required").spliterator(), false)
                                    .map(JsonNode::asText)
                                    .toList())
                    .as(name + " must keep every response key, including null values")
                    .containsExactlyInAnyOrderElementsOf(
                            schema.path("properties").properties().stream()
                                    .map(Map.Entry::getKey)
                                    .toList());
        }
    }

    @Test
    void nullableObjectsAcceptObjectAndNull() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        JsonNode properties = schemas.path("MovieDetailResponse").path("properties");
        for (String field : List.of("myRating", "recommendation")) {
            JsonNode property = properties.path(field);
            // $ref 옆의 type:null은 객체 스키마와 AND 결합되어 어떤 값도 허용하지 않는다.
            assertThat(property.path("type").asText()).isNotEqualTo("null");
            String schemaName =
                    field.equals("myRating") ? "MyRatingResponse" : "MovieRecommendationResponse";
            assertThat(StreamSupport.stream(property.path("anyOf").spliterator(), false).toList())
                    .containsExactlyInAnyOrder(
                            mapper.readTree(
                                    "{\"$ref\":\"#/components/schemas/" + schemaName + "\"}"),
                            mapper.readTree("{\"type\":\"null\"}"));
            assertThat(schemas.path(schemaName).path("type").asText()).isEqualTo("object");
        }
        for (JsonNode nullableEnum :
                List.of(
                        properties.path("myWatchStatus"),
                        schemas.path("MyRatingResponse").path("properties").path("visibility"))) {
            assertThat(
                            StreamSupport.stream(nullableEnum.path("type").spliterator(), false)
                                    .map(JsonNode::asText)
                                    .toList())
                    .contains("string", "null");
            assertThat(
                            nullableEnum.path("enum").isMissingNode()
                                    || StreamSupport.stream(
                                                    nullableEnum.path("enum").spliterator(), false)
                                            .anyMatch(JsonNode::isNull))
                    .isTrue();
        }
    }

    @Test
    void optionalAuthenticationMatchesRuntimePolicy() throws Exception {
        assertThat(SecurityConfig.isPublicApi(HttpMethod.GET, PATH)).isTrue();
        JsonNode security = document().path("paths").path(PATH).path("get").path("security");
        assertThat(security).isEqualTo(mapper.readTree("[{}, {\"bearerAuth\": []}]"));
    }

    @Test
    void otherOperationsKeepTheirSecurity() throws Exception {
        JsonNode document = document();
        assertThat(document.path("security").isEmpty()).isTrue();
        for (String path :
                List.of(
                        "/api/v1/otts",
                        "/api/v1/movies/filter-options",
                        "/api/v1/popcorn-flavors",
                        "/api/v1/users/nickname-availability")) {
            JsonNode operation = document.path("paths").path(path).path("get");
            assertThat(operation.isObject()).as(path).isTrue();
            assertThat(operation.path("security").isEmpty()).as(path).isTrue();
        }
        assertThat(
                        document.path("paths")
                                .path("/api/v1/auth/token/refresh")
                                .path("post")
                                .path("security")
                                .isEmpty())
                .isTrue();
        for (String path :
                List.of(
                        "/api/v1/movies/search",
                        "/api/v1/users/me",
                        "/api/v1/users/me/otts",
                        "/api/v1/parties")) {
            JsonNode operation = document.path("paths").path(path).path("get");
            assertThat(operation.isObject()).as(path).isTrue();
            assertThat(operation.path("security"))
                    .as(path)
                    .isEqualTo(mapper.readTree("[{\"bearerAuth\": []}]"));
        }
        assertThat(document.path("paths").path("/api/v1/parties").path("post").path("security"))
                .isEqualTo(mapper.readTree("[{\"bearerAuth\": []}]"));
    }

    private JsonNode document() throws Exception {
        String json =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith("application/json"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(
                Path.of("build/reports/movie-detail-openapi.json"), json, StandardCharsets.UTF_8);
        return mapper.readTree(json);
    }
}
