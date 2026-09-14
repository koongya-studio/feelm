package com.feelm.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.feelm.backend.global.security.TokenProvider;
import com.feelm.backend.support.SharedTestRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "feelm.auth.mock.enabled=false",
            "management.endpoints.web.exposure.include=health,prometheus",
            // Actuator test support disables non-simple registries unless the test opts in.
            "management.prometheus.metrics.export.enabled=true",
            "management.metrics.tags.application=backend",
            "management.metrics.distribution.percentiles-histogram.http.server.requests=true"
        })
@AutoConfigureMockMvc
@DisplayName("Prometheus 모니터링 엔드포인트 계약")
@SharedTestRuntime
class MonitoringEndpointContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TokenProvider tokenProvider;

    @Test
    @DisplayName("Prometheus는 JWT 없이 애플리케이션 메트릭을 수집할 수 있다")
    void prometheusEndpointIsAvailableWithoutJwt() throws Exception {
        // A completed request creates the HTTP histogram. Its health status depends on test Redis.
        mockMvc.perform(get("/actuator/health")).andReturn();

        String body =
                mockMvc.perform(get("/actuator/prometheus"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .contains("jvm_memory_used_bytes")
                .contains("http_server_requests_seconds_bucket")
                .contains("application=\"backend\"");
    }

    @Test
    @DisplayName("허용 목록 밖의 Actuator 엔드포인트는 노출하지 않는다")
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        String accessToken = tokenProvider.createAccessToken(328L);

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/env").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }
}
