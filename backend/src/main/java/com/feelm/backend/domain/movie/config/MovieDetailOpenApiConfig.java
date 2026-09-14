package com.feelm.backend.domain.movie.config;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API-030의 nullable 객체를 OpenAPI 3.1의 객체 또는 null 합집합으로 표현한다. */
@Configuration
public class MovieDetailOpenApiConfig {

    @Bean
    OpenApiCustomizer movieDetailNullableSchemas() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            Schema<?> detail = openApi.getComponents().getSchemas().get("MovieDetailResponse");
            if (detail == null || detail.getProperties() == null) {
                return;
            }
            // 현재 springdoc는 필드의 @Schema(nullable=true)를 type:null + $ref로 생성한다.
            // 이 조합은 AND여서 null도 객체도 허용하지 않는다. 이 API의 두 참조만 보완한다.
            for (String field : List.of("myRating", "recommendation")) {
                Schema<?> reference = detail.getProperties().get(field);
                if (reference != null && reference.get$ref() != null) {
                    detail.addProperty(
                            field,
                            new ComposedSchema()
                                    .description(reference.getDescription())
                                    .anyOf(
                                            List.of(
                                                    new Schema<>().$ref(reference.get$ref()),
                                                    new Schema<>().types(Set.of("null")))));
                }
            }
        };
    }
}
