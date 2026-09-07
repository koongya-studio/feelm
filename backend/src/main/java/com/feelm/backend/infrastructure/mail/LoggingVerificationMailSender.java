package com.feelm.backend.infrastructure.mail;

import lombok.extern.slf4j.Slf4j;

/**
 * 실제 발송 대신 인증 링크를 로그로 남긴다. SMTP 계정 없이 로컬에서 가입 흐름을 확인하기 위한 구현이다(플랜 결정 6).
 *
 * <p>이 구현은 인증 토큰이 담긴 링크를 로그에 남기므로 로컬 수동 확인 용도에 한정한다. dev·운영 프로파일에서 선택되면 {@link
 * VerificationMailModeGuard}가 기동을 실패시켜 운영 로그에 토큰이 남는 경로를 차단한다(컨벤션 12장, 플랜 결정 13). 테스트는 이 구현이 아니라 발송
 * 지점을 대체하는 스파이 빈에서 토큰을 얻으므로 테스트 로그에도 토큰이 남지 않는다.
 */
@Slf4j
class LoggingVerificationMailSender implements VerificationMailSender {

    private final VerificationMailProperties properties;

    LoggingVerificationMailSender(VerificationMailProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(String email, String verificationToken) {
        log.info(
                "인증 메일 발송을 로그로 대체합니다. 로컬 확인용 인증 링크: {}",
                properties.verificationLink(verificationToken));
    }
}
