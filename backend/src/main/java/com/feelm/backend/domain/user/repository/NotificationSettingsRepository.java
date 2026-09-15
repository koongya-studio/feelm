package com.feelm.backend.domain.user.repository;

import com.feelm.backend.domain.user.entity.NotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, Long> {

    /**
     * 사용자의 알림 설정 행을 지운다.
     *
     * <p>운영 스키마는 FK CASCADE로도 정리되지만, 계정 행을 삭제하는 경로(미인증 계정 재가입)가 DB 종류에 상관없이 같게 동작해야 하므로 애플리케이션에서
     * 명시적으로 지운다.
     */
    void deleteByUserId(Long userId);
}
