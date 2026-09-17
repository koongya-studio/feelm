package com.feelm.backend.domain.onboarding.dto.request;

import com.feelm.backend.domain.onboarding.entity.PreferenceChoice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * API-024 초기 취향 제출 요청.
 *
 * <p>제외한 카드는 배열에 넣지 않는다. 건너뛰기(`차차 찾아갈래요`)는 {@code skipped=true}와 빈 배열로 표현한다.
 *
 * <p>두 필드 모두 필수다. 명세의 요청 본문이 둘을 항상 포함하고, 빠진 필드를 기본값으로 채우면 "건너뛰지 않았는데 선택도 없는" 제출이 조용히 온보딩을 완료해 버린다.
 *
 * <p>스칼라는 계약에 적힌 JSON 타입으로만 읽는다. 이유와 기본 정책과의 차이는 {@link StrictScalarDeserializers}에 있다.
 */
public record OnboardingSubmitRequest(
        @NotNull(message = "선택 목록을 입력해 주세요.")
                @Size(max = MAX_PREFERENCES, message = "초기 취향은 40개까지 제출할 수 있습니다.")
                List<@NotNull @Valid Item> preferences,
        @NotNull(message = "건너뛰기 여부를 입력해 주세요.")
                @JsonDeserialize(using = StrictScalarDeserializers.SkippedDeserializer.class)
                Boolean skipped) {

    /** 한 번의 온보딩 제출 요청이 과도해지는 것을 막는 API-024의 방어 상한이다. */
    private static final int MAX_PREFERENCES = 40;

    /** 카드 한 장에 대한 선택. {@code preference}가 {@code LIKE}·{@code DISLIKE}가 아니면 본문 파싱 단계에서 거절된다. */
    public record Item(
            @NotNull(message = "movieId를 입력해 주세요.")
                    @Positive
                    @JsonDeserialize(using = StrictScalarDeserializers.MovieIdDeserializer.class)
                    Long movieId,
            @NotNull(message = "preference를 입력해 주세요.")
                    @JsonDeserialize(using = StrictScalarDeserializers.PreferenceDeserializer.class)
                    PreferenceChoice preference) {}

    @AssertTrue(message = "같은 영화를 두 번 선택할 수 없습니다.")
    public boolean isMovieIdUnique() {
        if (preferences == null) {
            return true;
        }
        return preferences.stream()
                        .filter(item -> item != null && item.movieId() != null)
                        .map(Item::movieId)
                        .distinct()
                        .count()
                == preferences.stream()
                        .filter(item -> item != null && item.movieId() != null)
                        .count();
    }

    @AssertTrue(message = "건너뛴 제출에는 선택을 포함할 수 없습니다.")
    public boolean isSkipConsistent() {
        return skipped == null || !skipped || preferences == null || preferences.isEmpty();
    }
}
