package com.feelm.backend.domain.onboarding.dto.response;

import java.util.List;

/** API-023 초기 취향 카드 목록 응답. 노출 순서가 곧 배열 순서다. */
public record OnboardingCardListResponse(List<Item> items) {

    public record Item(
            Long movieId,
            String title,
            String posterUrl,
            Integer releaseYear,
            List<String> genres,
            String flavorCode) {

        public static Item of(OnboardingCardQueryResult card, List<String> genres) {
            return new Item(
                    card.movieId(),
                    card.title(),
                    card.posterUrl(),
                    card.releaseYear(),
                    genres,
                    card.flavorCode());
        }
    }
}
