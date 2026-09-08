package com.feelm.backend.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;

/**
 * 실제 발송 대신 재설정 링크를 로그로 남긴다. SMTP 계정 없이 로컬에서 재설정 흐름을 확인하기 위한 구현이다.
 *
 * <p>이 구현은 재설정 토큰이 담긴 링크를 로그에 남기므로 로컬 수동 확인 용도에 한정한다. dev·운영 프로파일에서 선택되면 {@link
 * VerificationMailModeGuard}가 기동을 실패시킨다. 두 메일이 같은 {@code feelm.mail.mode}를 쓰므로 그 검사가 이 구현까지 함께
 * 막는다(컨벤션 12장). 테스트는 이 구현이 아니라 발송 지점을 대체하는 스파이 빈에서 토큰을 얻으므로 테스트 로그에도 토큰이 남지 않는다.
 */
@Slf4j
class LoggingPasswordResetMailSender implements PasswordResetMailSender {

    private final VerificationMailProperties properties;

    LoggingPasswordResetMailSender(VerificationMailProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(String email, String passwordResetToken) {
        log.info(
                "비밀번호 재설정 메일 발송을 로그로 대체합니다. 로컬 확인용 재설정 링크: {}",
                properties.passwordResetLink(passwordResetToken));
    }
}
