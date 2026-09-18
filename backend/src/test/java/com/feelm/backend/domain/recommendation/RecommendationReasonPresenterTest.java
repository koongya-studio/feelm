package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.recommendation.service.RecommendationReasonPresenter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RecommendationReasonPresenterTest {

    private final RecommendationReasonPresenter presenter =
            new RecommendationReasonPresenter(new ObjectMapper());

    @Test
    void exposesOnlyUserFacingSnapshot() {
        JsonNode reason =
                presenter.present(
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55],
                        "matchedGenreCodes":["THRILLER"]}],"summary":"스릴러 장르가 같아요.",
                        "factors":[{"type":"GENRE","label":"스릴러"}]}
                        """);

        assertThat(reason.path("summary").textValue()).isEqualTo("스릴러 장르가 같아요.");
        assertThat(reason.path("factors")).hasSize(1);
        assertThat(reason.has("reasons")).isFalse();
    }

    @Test
    void legacyRawReasonUsesNeutralFallback() {
        JsonNode reason = presenter.present("{\"reasons\":[{\"code\":\"TOP_RATED_SOURCE\"}]}");

        assertThat(reason.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        assertThat(reason.path("factors")).isEmpty();
    }
}
