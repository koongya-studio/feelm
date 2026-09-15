package com.feelm.backend.domain.onboarding.repository;

import com.feelm.backend.domain.onboarding.entity.OnboardingPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingPreferenceRepository extends JpaRepository<OnboardingPreference, Long> {

    /** 탈퇴(API-013)가 온보딩에서 모은 초기 취향을 지운다. 재가입은 새 계정이라 이어지지 않는다. */
    void deleteByUserId(Long userId);
}
