package com.feelm.backend.infrastructure.mail;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 발송된 인증 토큰을 기록하는 테스트용 발송 구현이다.
 *
 * <p>토큰 원문은 DB에 해시로만 남아(플랜 결정 8) 테스트가 되돌릴 수 없으므로, 발송 지점을 대체해 원문을 얻는다. 로그를 파싱하는 방식 대신 이 방식을 쓰는 이유는 두
 * 가지다(결정 13). 인증 토큰을 로그에 남기지 않는 컨벤션 12장과 충돌하지 않고, 발송 횟수 검증도 로그 출력 형식이 아니라 실제 발송 호출에 근거하게 된다.
 */
public class RecordingVerificationMailSender implements VerificationMailSender {

    private final RecordingMailSends sends = new RecordingMailSends();

    @Override
    public void send(String email, String verificationToken) {
        sends.record(verificationToken);
    }

    /** 발송 순서대로 모은 토큰 원문. 발송 횟수 검증에도 쓴다. */
    public List<String> tokens() {
        return sends.tokens();
    }

    /** 컨텍스트를 공유하는 테스트 사이에 기록·래치·지시한 실패가 남지 않도록 각 테스트 시작 시점에 비운다. */
    public void clear() {
        sends.clear();
    }

    /** 다음 발송들을 발송 지점에서 붙잡는다(S15P21E106-282 T1~T3). */
    public void blockSends() {
        sends.blockSends();
    }

    public void releaseSends() {
        sends.releaseSends();
    }

    /** 다음 발송 한 건을 실패시킨다(S15P21E106-282 T4). */
    public void failNextSend() {
        sends.failNextSend();
    }

    /** 운영 구현({@code log}·{@code smtp}) 대신 이 스파이가 주입되도록 등록한다. */
    @TestConfiguration
    public static class Registrar {

        @Bean
        @Primary
        RecordingVerificationMailSender recordingVerificationMailSender() {
            return new RecordingVerificationMailSender();
        }
    }
}
