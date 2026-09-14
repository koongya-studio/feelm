package com.feelm.backend.domain.onboarding.dto.response;

/**
 * 카드 한 장의 조회 결과(API-023). 장르는 별도 조회로 채우므로 여기에는 없다.
 *
 * <p>{@code releaseYear}는 {@code movies.release_date}가 비어 있으면 {@code null}이다.
 */
public record OnboardingCardQueryResult(
        Long movieId, String title, String posterUrl, Integer releaseYear, String flavorCode) {}
