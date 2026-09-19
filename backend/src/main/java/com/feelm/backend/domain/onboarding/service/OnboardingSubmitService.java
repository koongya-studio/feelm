package com.feelm.backend.domain.onboarding.service;

import com.feelm.backend.domain.onboarding.dto.request.OnboardingSubmitRequest;
import com.feelm.backend.domain.onboarding.dto.response.OnboardingSubmitResponse;
import com.feelm.backend.domain.onboarding.entity.OnboardingPreference;
import com.feelm.backend.domain.onboarding.entity.PreferenceChoice;
import com.feelm.backend.domain.onboarding.repository.OnboardingPreferenceRepository;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.domain.watch.service.OnboardingRatingWriter;
import com.feelm.backend.domain.watch.service.OnboardingRatingWriter.RatingChoice;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ConstraintViolations;
import com.feelm.backend.global.error.ErrorCode;
import com.feelm.backend.infrastructure.kafka.KafkaEventPublisher;
import com.feelm.backend.infrastructure.kafka.KafkaEventType;
import com.feelm.backend.infrastructure.kafka.OnboardingEventPayload;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초기 취향 제출(API-024).
 *
 * <p>선택 저장, 온보딩 완료 전이와 추천 파이프라인으로 보낼 이벤트가 하나의 사실이라 한 트랜잭션에 둔다. 이벤트는 아웃박스 행으로 함께 저장되므로 저장이 롤백되면 발행도
 * 남지 않는다(ADR-0018).
 */
@Service
@RequiredArgsConstructor
public class OnboardingSubmitService {

    private final OnboardingPreferenceRepository onboardingPreferenceRepository;
    private final UserRepository userRepository;
    private final OnboardingRatingWriter onboardingRatingWriter;
    private final KafkaEventPublisher kafkaEventPublisher;

    /**
     * 선택을 저장하고 온보딩을 완료 처리한다. 건너뛴 제출은 저장 없이 완료만 한다.
     *
     * <p>완료 판정과 전이 사이에 같은 사용자의 다른 제출이 끼어들면 두 요청이 모두 성공해 선택이 합쳐진다. 이를 막기 위해 계정 행을 잠근 뒤 판정한다. 잠금 구간에
     * 외부 호출은 없다.
     *
     * @throws BusinessException 요청자가 활성 계정이 아니면 {@code UNAUTHORIZED}, 이미 온보딩을 마쳤으면 {@code
     *     ONBOARDING_ALREADY_COMPLETED}, 존재하지 않는 영화를 선택하면 {@code VALIDATION_FAILED}
     */
    @Transactional
    public OnboardingSubmitResponse submit(long userId, OnboardingSubmitRequest request) {
        List<OnboardingSubmitRequest.Item> choices = request.preferences();
        // 활성 계정 판정과 직렬화를 한 번의 조회로 처리한다.
        User user =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (user.isOnboardingCompleted()) {
            throw new BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }
        onboardingRatingWriter.createMissing(
                userId,
                choices.stream()
                        .map(
                                choice ->
                                        new RatingChoice(
                                                choice.movieId(), scoreOf(choice.preference())))
                        .toList());
        try {
            // 카드를 받은 뒤 영화가 사라지면 FK 제약이 최종 판정을 내린다. 제약 위반이 여기까지 올라오도록 저장 시점에 flush 한다.
            onboardingPreferenceRepository.saveAllAndFlush(
                    choices.stream()
                            .map(
                                    choice ->
                                            OnboardingPreference.of(
                                                    userId, choice.movieId(), choice.preference()))
                            .toList());
        } catch (DataIntegrityViolationException exception) {
            throw ConstraintViolations.onboardingMovieReferenceOf(exception);
        }
        user.completeOnboarding();
        if (!choices.isEmpty()) {
            // 선택이 없으면 Consumer가 읽을 행도 없어 Fold-in 입력이 생기지 않는다(이벤트·Redis 계약 7장).
            kafkaEventPublisher.publish(
                    KafkaEventType.ONBOARDING, userId, new OnboardingEventPayload(choices.size()));
        }
        return new OnboardingSubmitResponse(true, choices.size(), request.skipped());
    }

    private static BigDecimal scoreOf(PreferenceChoice preference) {
        return switch (preference) {
            case LIKE -> new BigDecimal("4.0");
            case DISLIKE -> new BigDecimal("2.0");
        };
    }
}
