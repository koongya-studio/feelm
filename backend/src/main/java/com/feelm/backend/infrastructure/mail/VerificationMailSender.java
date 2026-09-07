package com.feelm.backend.infrastructure.mail;

/**
 * 가입 이메일 인증 메일을 보낸다.
 *
 * <p>구현 교체 지점을 인터페이스로 둔 이유는 두 가지다. 하나는 실제 SMTP 발송과 로컬 확인용 로그 출력을 환경별로 바꿔야 하기 때문이고(플랜 결정 5·6), 다른
 * 하나는 토큰 원문을 DB에서 되돌릴 수 없어(결정 8) 테스트가 발송 지점을 대체해 원문을 확인해야 하기 때문이다(결정 13).
 */
public interface VerificationMailSender {

    /**
     * 인증 링크가 담긴 메일을 보낸다.
     *
     * @param email 수신 주소
     * @param verificationToken 인증 토큰 원문. 링크에만 사용하고 저장·로그에 남기지 않는다(컨벤션 12장)
     */
    void send(String email, String verificationToken);
}
