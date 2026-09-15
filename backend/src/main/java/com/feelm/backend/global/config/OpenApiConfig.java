package com.feelm.backend.global.config;

import com.feelm.backend.global.security.SecurityConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Collections;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** 실행 중인 제품 API의 OpenAPI 문서를 구성한다. */
@Configuration
public class OpenApiConfig {

    static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    OpenAPI feelmOpenApi() {
        SecurityScheme bearerJwt =
                new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT");

        return new OpenAPI()
                .info(new Info().title("FEELM API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH_SCHEME, bearerJwt));
    }

    @Bean
    OpenApiCustomizer productApiPathsOnly() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths()
                    .keySet()
                    .removeIf(
                            path ->
                                    !path.startsWith("/api/v1/")
                                            || path.startsWith("/api/v1/test/"));
            openApi.getPaths()
                    .forEach(
                            (path, pathItem) ->
                                    pathItem.readOperationsMap()
                                            .forEach(
                                                    (method, operation) -> {
                                                        HttpMethod httpMethod =
                                                                HttpMethod.valueOf(method.name());
                                                        if (SecurityConfig
                                                                .isOptionalAuthenticationApi(
                                                                        httpMethod, path)) {
                                                            operation.setSecurity(
                                                                    List.of(
                                                                            new SecurityRequirement(),
                                                                            new SecurityRequirement()
                                                                                    .addList(
                                                                                            BEARER_AUTH_SCHEME)));
                                                        } else if (SecurityConfig.isPublicApi(
                                                                httpMethod, path)) {
                                                            operation.setSecurity(
                                                                    Collections.emptyList());
                                                        } else {
                                                            operation.setSecurity(
                                                                    List.of(
                                                                            new SecurityRequirement()
                                                                                    .addList(
                                                                                            BEARER_AUTH_SCHEME)));
                                                        }
                                                    }));
        };
    }
}
