package com.feelm.backend.infrastructure.mail;

/**
 * 비밀번호 재설정 메일을 보낸다.
 *
 * <p>{@link VerificationMailSender}와 같은 이유로 인터페이스를 둔다. 환경별로 SMTP 발송과 로컬 확인용 로그 출력을 바꿔야 하고, 토큰 원문을
 * DB에서 되돌릴 수 없어 테스트가 발송 지점을 대체해 원문을 확인해야 한다.
 *
 * <p>가입 인증 메일과 하나의 인터페이스로 합치지 않은 이유는 메일 종류마다 링크·본문·수명이 달라 한쪽 변경이 다른 쪽 서명으로 번지지 않게 하기 위해서다(플랜 결정 E).
 */
public interface PasswordResetMailSender {

    /**
     * 재설정 링크가 담긴 메일을 보낸다.
     *
     * @param email 수신 주소
     * @param passwordResetToken 재설정 토큰 원문. 링크에만 사용하고 저장·로그에 남기지 않는다(컨벤션 12장)
     */
    void send(String email, String passwordResetToken);
}
