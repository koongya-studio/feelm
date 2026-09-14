package com.feelm.backend.domain.onboarding.service;

import com.feelm.backend.domain.onboarding.dto.request.OnboardingCardCriteria;
import com.feelm.backend.domain.onboarding.dto.response.OnboardingCardListResponse;
import com.feelm.backend.domain.onboarding.dto.response.OnboardingCardQueryResult;
import com.feelm.backend.domain.onboarding.repository.OnboardingCardRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 온보딩 카드 목록 조회(API-023). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OnboardingCardService {

    private final OnboardingCardRepository onboardingCardRepository;
    private final UserRepository userRepository;

    /**
     * 좋아요·싫어요를 선택할 영화 카드를 읽는다.
     *
     * <p>온보딩을 이미 마친 사용자는 카드도 받지 못한다. 재실행을 허용하지 않으므로 제출(API-024)만 막으면 사용자가 카드를 고른 뒤에야 거절당한다.
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}, 이미 온보딩을 마쳤으면 {@code
     *     ONBOARDING_ALREADY_COMPLETED}
     */
    public OnboardingCardListResponse getCards(long userId, OnboardingCardCriteria criteria) {
        User user =
                userRepository
                        .findById(userId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (user.isOnboardingCompleted()) {
            throw new BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }
        List<OnboardingCardQueryResult> cards = onboardingCardRepository.findCards(criteria.size());
        Map<Long, List<String>> genreNames =
                onboardingCardRepository.findGenreNames(
                        cards.stream().map(OnboardingCardQueryResult::movieId).toList());
        return new OnboardingCardListResponse(
                cards.stream()
                        .map(
                                card ->
                                        OnboardingCardListResponse.Item.of(
                                                card,
                                                genreNames.getOrDefault(card.movieId(), List.of())))
                        .toList());
    }
}
