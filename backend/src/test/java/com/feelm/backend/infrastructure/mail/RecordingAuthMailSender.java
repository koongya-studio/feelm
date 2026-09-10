package com.feelm.backend.infrastructure.mail;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 발송된 토큰을 메일 종류별로 기록하는 테스트용 발송 구현이다.
 *
 * <p>토큰 원문은 DB에 해시로만 남아(플랜 결정 8) 테스트가 되돌릴 수 없으므로, 발송 지점을 대체해 원문을 얻는다. 로그를 파싱하는 방식 대신 이 방식을 쓰는 이유는 두
 * 가지다(결정 13). 1회용 토큰을 로그에 남기지 않는 컨벤션 12장과 충돌하지 않고, 발송 횟수 검증도 로그 출력 형식이 아니라 실제 발송 호출에 근거하게 된다.
 *
 * <p>기록·래치·지시한 실패는 <b>종류별로 분리</b>한다(S15P21E106-284). 발송 구현이 하나로 합쳐졌어도, 인증 메일 발송만 붙잡아 두고 응답을 확인하는
 * 테스트({@code AsyncMailDispatchTest})가 재설정 메일 발송까지 멈추면 안 된다.
 */
public class RecordingAuthMailSender implements AuthMailSender {

    private final Map<MailKind, RecordingMailSends> sendsByKind = new EnumMap<>(MailKind.class);

    public RecordingAuthMailSender() {
        for (MailKind kind : MailKind.values()) {
            sendsByKind.put(kind, new RecordingMailSends());
        }
    }

    @Override
    public void send(MailKind kind, String email, String rawToken) {
        sends(kind).record(rawToken);
    }

    /** 해당 종류의 발송 기록과 제어 수단. 기록은 종류별로 분리돼 있다. */
    public RecordingMailSends sends(MailKind kind) {
        return sendsByKind.get(kind);
    }

    /** 컨텍스트를 공유하는 테스트 사이에 기록·래치·지시한 실패가 남지 않도록 각 테스트 시작 시점에 비운다. */
    public void clearAll() {
        sendsByKind.values().forEach(RecordingMailSends::clear);
    }

    /** 붙잡아 둔 발송을 모두 풀어 준다. 비우기 전에 진행 중인 발송을 끝내기 위해 쓴다. */
    public void releaseAll() {
        sendsByKind.values().forEach(RecordingMailSends::releaseSends);
    }

    /** 운영 구현({@code log}·{@code smtp}) 대신 이 스파이가 주입되도록 등록한다. */
    @TestConfiguration
    public static class Registrar {

        @Bean
        @Primary
        RecordingAuthMailSender recordingAuthMailSender() {
            return new RecordingAuthMailSender();
        }
    }
}
