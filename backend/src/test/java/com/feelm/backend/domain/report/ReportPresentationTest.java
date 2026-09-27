package com.feelm.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.feelm.backend.domain.report.model.ReportContent;
import com.feelm.backend.domain.report.model.ReportPresentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ReportPresentationTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void hiddenCountsAndGenerationMetadataDoNotCreateDuplicateReports() {
        String content =
                ReportApiContractSupport.CONTENT.replace(
                        "\"lowSample\": true", "\"lowSample\": false");
        var changed =
                content.replace("\"ratingCount\": 3", "\"ratingCount\": 99")
                        .replace("\"newFlavorCount\": 1", "\"newFlavorCount\": 8")
                        .replace("\"averageScore\": 4.0", "\"averageScore\": 2.5")
                        .replace("\"frameCount\": 3", "\"frameCount\": 99")
                        .replace("\"code\": \"ROMANCE\"", "\"code\": \"INTERNAL\"")
                        .replace("\"personId\": 40", "\"personId\": 999")
                        .replace(
                                "\"flavorChanges\": []",
                                "\"flavorChanges\": [{\"flavorCode\":\"X\",\"before\":1,\"after\":2}]");
        assertThat(presentation(changed)).isEqualTo(presentation(content));
        String metadata =
                content.substring(0, content.lastIndexOf('}'))
                        + """
                , "generation": {"contentVersion":999,"snapshotAt":"2035-01-01T00:00:00Z",
                  "cutoffAt":"2035-01-01T00:00:00Z","finalSnapshot":false}
                }
                """;
        assertThat(presentation(metadata)).isEqualTo(presentation(content));
    }

    @Test
    void visibleLowSampleCountMustMatch() {
        var content = ReportApiContractSupport.CONTENT;
        assertThat(presentation(content.replace("\"frameCount\": 3", "\"frameCount\": 4")))
                .isNotEqualTo(presentation(content));
        assertThat(presentation(content.replace("\"lowSample\": true", "\"lowSample\": false")))
                .isNotEqualTo(presentation(content));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ROMANCE",
                "안락의자 탐정",
                "익숙한 자리에서 잔뜩 봤고, 별점은 냉정했어요",
                "익숙한 영화 안에서 어두운 쪽을 많이 봤고, 별점은 박했어요.",
                "익숙함",
                "RDMV",
                "감독",
                "배우"
            })
    void displayedNamesAndCharacterAnalysisMustMatch(String text) {
        var content = ReportApiContractSupport.CONTENT;
        assertThat(content).contains(text);
        assertThat(presentation(content.replace(text, text + " 변경")))
                .isNotEqualTo(presentation(content));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "genreShares",
                "countryShares",
                "eraShares",
                "keywords",
                "comparisons",
                "topActors",
                "topDirectors",
                "fourCut"
            })
    void everyBackAnalysisAndFourCutImagesMustMatch(String field) {
        var content = mapper.readTree(ReportApiContractSupport.CONTENT);
        var changed = (tools.jackson.databind.node.ObjectNode) content.deepCopy();
        String value =
                switch (field) {
                    case "genreShares", "countryShares", "eraShares" ->
                            "[{\"label\":\"변경\",\"share\":30}]";
                    case "keywords" ->
                            "[{\"code\":\"K\",\"label\":\"키워드\",\"rationale\":\"다른 근거\"}]";
                    case "comparisons" -> "[{\"label\":\"비교\",\"delta\":3,\"isNew\":true}]";
                    case "topActors" -> "[{\"personId\":40,\"name\":\"배우\",\"count\":3}]";
                    case "topDirectors" -> "[{\"personId\":30,\"name\":\"감독\",\"count\":3}]";
                    default ->
                            "{\"genre\":{\"name\":\"로맨스\",\"displayName\":\"ROMANCE\",\"image\":{\"url\":\"/different.jpg\",\"position\":{\"x\":1,\"y\":2},\"zoom\":1.2}}}";
                };
        changed.set(field, mapper.readTree(value));
        assertThat(ReportPresentation.from(mapper.treeToValue(changed, ReportContent.class)))
                .isNotEqualTo(
                        ReportPresentation.from(mapper.treeToValue(content, ReportContent.class)));
        assertThat(
                        ReportPresentation.from(mapper.treeToValue(changed, ReportContent.class))
                                .hasSameFront(
                                        ReportPresentation.from(
                                                mapper.treeToValue(content, ReportContent.class))))
                .isEqualTo(!field.equals("fourCut"));
    }

    @Test
    void sameFrontIgnoresBackButIncludesVisibleImagesAndCharacterCode() {
        var original = presentation(ReportApiContractSupport.CONTENT);
        var changedCode =
                presentation(
                        ReportApiContractSupport.CONTENT.replace(
                                "\"code\": \"RDMV\"", "\"code\": \"ELHV\""));
        assertThat(changedCode.fourCut()).isEqualTo(original.fourCut());
        assertThat(changedCode.hasSameFront(original)).isFalse();
        var changedBack =
                presentation(
                        ReportApiContractSupport.CONTENT.replace(
                                "익숙한 영화 안에서 어두운 쪽을 많이 봤고, 별점은 박했어요.", "뒷면 설명 변경"));
        assertThat(changedBack).isNotEqualTo(original);
        assertThat(changedBack.hasSameFront(original)).isTrue();
        assertThat(presentation("{}").hasSameFront(presentation("{}"))).isTrue();
    }

    @Test
    void absentLegacyAnalysisFieldsAreHandled() {
        assertThat(presentation("{}"))
                .isEqualTo(presentation("{\"topActors\":[],\"keywords\":[]}"));
    }

    private ReportPresentation presentation(String content) {
        return ReportPresentation.from(mapper.readValue(content, ReportContent.class));
    }
}
