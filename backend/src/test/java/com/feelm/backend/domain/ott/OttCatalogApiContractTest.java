package com.feelm.backend.domain.ott;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.support.SharedPostgresContractTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** API-025: 실제 마이그레이션과 PostgreSQL을 사용하는 OTT 기준정보 HTTP 계약 테스트. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
@AutoConfigureMockMvc
@DisplayName("API-025 OTT 목록 조회")
class OttCatalogApiContractTest extends SharedPostgresContractTest {

    private static final String OTTS_PATH = "/api/v1/otts";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    /** 확정된 지원 OTT 다섯 개를 적재 순서와 다르게 넣어 정렬이 식별자 기준임을 드러낸다. */
    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE TABLE otts RESTART IDENTITY CASCADE");
        ott(3, "WATCHA", "왓챠", "https://image.test/watcha.png", 97);
        ott(1, "NETFLIX", "넷플릭스", "https://image.test/netflix.png", 8);
        ott(5, "TVING", "티빙", null, 1883);
        ott(2, "DISNEY_PLUS", "디즈니+", "https://image.test/disney.png", 337);
        ott(4, "WAVVE", "웨이브", "https://image.test/wavve.png", 356);
    }

    @Test
    @DisplayName("T1 인증 없이 조회할 수 있고 식별자 오름차순으로 내려준다")
    void listsOttsWithoutAuthentication() throws Exception {
        String body =
                mvc.perform(get(OTTS_PATH))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items", hasSize(5)))
                        .andExpect(jsonPath("$.items[0].ottId").value(1))
                        .andExpect(jsonPath("$.items[0].code").value("NETFLIX"))
                        .andExpect(jsonPath("$.items[0].name").value("넷플릭스"))
                        .andExpect(
                                jsonPath("$.items[0].logoUrl")
                                        .value("https://image.test/netflix.png"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode items = objectMapper.readTree(body).get("items");
        List<Integer> ids = new ArrayList<>();
        items.forEach(
                item -> {
                    ids.add(item.get("ottId").asInt());
                    // 명세에 없는 필드가 새어 나가지 않는지 본다. tmdb_provider_id는 적재 전용 값이다.
                    assertThat(item.size()).isEqualTo(4);
                    assertThat(item.get("code").asText()).isNotBlank();
                    assertThat(item.get("name").asText()).isNotBlank();
                    assertThat(item.has("logoUrl")).isTrue();
                });
        assertThat(ids).containsExactly(1, 2, 3, 4, 5);
        // 로고가 없는 행은 null로 내려간다.
        assertThat(items.get(4).get("logoUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("T2 기준정보이므로 하루짜리 공유 캐시 헤더를 붙인다")
    void sendsCacheControlHeader() throws Exception {
        mvc.perform(get(OTTS_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
    }

    private void ott(long id, String code, String name, String logoUrl, int tmdbProviderId) {
        jdbc.update(
                "INSERT INTO otts(id,code,name,logo_url,tmdb_provider_id) VALUES (?,?,?,?,?)",
                id,
                code,
                name,
                logoUrl,
                tmdbProviderId);
    }
}
