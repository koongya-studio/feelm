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
                        "TASTE",
                        """
                        {"reasons":[{"code":"SHARED_GENRE","sourceMovieIds":[55],
                        "matchedGenreCodes":["THRILLER"]}],"summary":"스릴러 장르가 같아요.",
                        "factors":[{"type":"GENRE","label":"스릴러"}],
                        "usedEvidenceIds":["sha256:internal"],"modelKind":"GBT",
                        "modelVersion":"gbt-v26","rankingVersion":"internal",
                        "explanationVersion":"internal","evidence":{"secret":true},
                        "_reasonPending":true}
                        """);

        assertThat(reason.path("summary").textValue()).isEqualTo("스릴러 장르가 같아요.");
        assertThat(reason.path("factors")).hasSize(1);
        assertThat(reason.has("reasons")).isFalse();
        assertThat(reason.has("usedEvidenceIds")).isFalse();
        assertThat(reason.has("modelKind")).isFalse();
        assertThat(reason.has("modelVersion")).isFalse();
        assertThat(reason.has("rankingVersion")).isFalse();
        assertThat(reason.has("explanationVersion")).isFalse();
        assertThat(reason.has("evidence")).isFalse();
        assertThat(reason.has("_reasonPending")).isFalse();
    }

    @Test
    void legacyRawReasonUsesTypeFallback() {
        String storedReason = "{\"reasons\":[{\"code\":\"TOP_RATED_SOURCE\"}]}";

        JsonNode taste = presenter.present("TASTE", storedReason);
        JsonNode discovery = presenter.present("DISCOVERY", storedReason);
        JsonNode party = presenter.present("PARTY", storedReason);

        assertThat(taste.path("summary").textValue()).isEqualTo("취향 데이터를 바탕으로 영화를 골라봤어요.");
        assertThat(discovery.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        assertThat(party.path("summary").textValue()).isEqualTo("새로운 영화를 만나보세요.");
        assertThat(taste.path("factors")).isEmpty();
        assertThat(discovery.path("factors")).isEmpty();
        assertThat(party.path("factors")).isEmpty();
    }
}
