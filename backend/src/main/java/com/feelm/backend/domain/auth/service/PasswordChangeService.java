package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.UserRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API-012 비밀번호 변경. */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    /**
     * 현재 비밀번호를 확인한 뒤 새 비밀번호로 바꾸고 해당 사용자의 모든 Refresh Token을 폐기한다.
     *
     * <p>폐기 범위가 전체인 이유는 이 엔드포인트에서 현재 세션을 식별할 수 없기 때문이다. Refresh 쿠키의 {@code Path}가 {@code
     * /api/v1/auth}라 이 요청에는 실리지 않고, Access Token에는 세션 식별자가 없다. 계약의 "현재 세션 제외"는 가정 표기였으며 이 동작으로
     * 정정했다(API-012 처리 규칙, 플랜 결정 A-1).
     *
     * <p>검사 순서는 계정 상태 → 비밀번호 보유 여부 → 대조다. 활성 계정이 아니면 그 뒤를 보지 않고 {@code UNAUTHORIZED}로 거절한다. Access
     * Token은 상태를 담지 않아 탈퇴 뒤에도 잔여 수명 동안 서명이 유효하므로, 이 검사가 없으면 탈퇴한 계정이 비밀번호를 바꿀 수 있다. 로그인(API-004)과
     * 재설정(API-011)도 탈퇴 계정을 같은 방향으로 막는다(API-012 처리 규칙).
     *
     * <p>해시가 없는 계정에는 대조를 시도할 수 없고, 소셜 전용 계정에는 불일치(401)가 아니라 수단이 없다는 사실(409)을 알려 주어야 한다.
     *
     * <p>계정 행을 잠그고 읽어 같은 사용자의 동시 변경 요청이 서로의 결과를 덮어쓰지 않게 한다.
     */
    @Transactional
    public void change(long userId, String currentPassword, String newPassword) {
        User user =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (!user.hasPassword()) {
            throw new BusinessException(ErrorCode.PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenService.revokeAll(userId);
    }
}
