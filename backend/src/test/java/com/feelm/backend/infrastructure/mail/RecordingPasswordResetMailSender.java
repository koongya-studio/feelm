package com.feelm.backend.infrastructure.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 발송된 비밀번호 재설정 토큰을 기록하는 테스트용 발송 구현이다.
 *
 * <p>재설정 토큰 원문도 DB에는 해시로만 남아 테스트가 되돌릴 수 없으므로, 인증 메일과 같은 방식으로 발송 지점을 대체해 원문을 얻는다({@link
 * RecordingVerificationMailSender}와 같은 근거).
 */
public class RecordingPasswordResetMailSender implements PasswordResetMailSender {

    private final List<String> tokens = new CopyOnWriteArrayList<>();

    @Override
    public void send(String email, String passwordResetToken) {
        tokens.add(passwordResetToken);
    }

    /** 발송 순서대로 모은 토큰 원문. 발송 횟수 검증에도 쓴다. */
    public List<String> tokens() {
        return List.copyOf(tokens);
    }

    /** 컨텍스트를 공유하는 테스트 사이에 기록이 남지 않도록 각 테스트 시작 시점에 비운다. */
    public void clear() {
        tokens.clear();
    }

    /** 운영 구현({@code log}·{@code smtp}) 대신 이 스파이가 주입되도록 등록한다. */
    @TestConfiguration
    public static class Registrar {

        @Bean
        @Primary
        RecordingPasswordResetMailSender recordingPasswordResetMailSender() {
            return new RecordingPasswordResetMailSender();
        }
    }
}
