package com.feelm.backend.domain.auth.service;

import com.feelm.backend.domain.auth.PasswordResetTokenIssuer;
import com.feelm.backend.domain.auth.RefreshTokenService;
import com.feelm.backend.domain.user.entity.User;
import com.feelm.backend.domain.user.repository.PasswordCredentials;
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
    private final PasswordResetTokenIssuer resetTokenIssuer;

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
     *
     * <p>미사용 비밀번호 재설정 토큰이 있으면 함께 무효화한다(151 리뷰 R-8). 재설정 메일이 유출된 상태에서 이 경로로 비밀번호를 바꿔도 남은 링크가 최대 30분
     * 더 유효해서는 안 되기 때문이다. 이 호출을 계정 행 잠금보다 먼저 두는 이유는 잠금 순서 때문이다. 재설정(API-011)이 토큰→계정 순서로 잠그므로 (이메일 인증
     * 재설정과 동일), 이 메서드가 반대 순서(계정→토큰)로 잠그면 같은 사용자에 대한 재설정과 비밀번호 변경이 동시에 들어왔을 때 교착할 수 있다. 검증에 실패해 예외가
     * 나면 무효화도 함께 롤백된다.
     *
     * <p>그래서 잠금 없는 사전 검사를 먼저 한다(283 리뷰 발견 2). 현재 비밀번호가 틀렸거나 계정이 비활성인 요청은 애초에 실패할 것이므로, 토큰 잠금(사용자 단위
     * JVM 락, {@link PasswordResetTokenIssuer#invalidateAllUnused})조차 걸 필요가 없다. 사전 검사는 행 잠금이 없는 일반
     * 조회라 계정 행을 먼저 잠그는 것이 아니며(토큰→계정 잠금 순서 불변식은 유지된다), 사전 검사 통과 뒤에도 잠금 있는 최종 검사를 그대로 다시 거쳐 판단을 내린다 —
     * 사전 검사는 통과했지만 그 사이 다른 트랜잭션이 상태를 바꿨을 가능성까지 커버하기 위해서다.
     *
     * <p>그 최종 검사가 실제로 최신 값을 보도록 사전 검사는 Entity가 아니라 {@link PasswordCredentials}로 읽는다. 이유는 {@link
     * UserRepository#findPasswordCredentialsById}에 있다(MR !214 리뷰 2).
     */
    @Transactional
    public void change(long userId, String currentPassword, String newPassword) {
        PasswordCredentials precheck =
                userRepository
                        .findPasswordCredentialsById(userId)
                        .filter(PasswordCredentials::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        assertCanChangePassword(precheck.passwordHash(), currentPassword);

        resetTokenIssuer.invalidateAllUnused(userId);

        User user =
                userRepository
                        .findByIdForUpdate(userId)
                        .filter(User::isActive)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        assertCanChangePassword(user.getPasswordHash(), currentPassword);

        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenService.revokeAll(userId);
    }

    /** 해시가 없으면 이메일 로그인 수단이 없는 계정이다({@code User.hasPassword()}와 같은 판정). */
    private void assertCanChangePassword(String passwordHash, String currentPassword) {
        if (passwordHash == null) {
            throw new BusinessException(ErrorCode.PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(currentPassword, passwordHash)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
    }
}
