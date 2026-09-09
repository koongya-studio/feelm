package com.feelm.backend.infrastructure.mail;

/**
 * 계정 메일의 종류다. 발송이 요청 스레드를 떠나면서 실패·유실 기록도 발송 지점이 아니라 {@link MailDispatcher}에서 남게 되어, 종류마다 다른 문구와 사용자
 * 복구 경로를 이 열거형이 들고 있는다.
 *
 * <p>복구 경로를 값으로 둔 이유는 발송 실패 로그가 "무엇이 실패했고 사용자가 무엇으로 되돌릴 수 있는지"를 함께 말해야 하기 때문이다. 메일 종류가 늘면 여기에 추가한다.
 */
public enum MailKind {
    /** 가입 인증 메일(API-001·API-003). */
    VERIFICATION("인증 메일", "재발송(API-003)"),

    /** 비밀번호 재설정 메일(API-010). */
    PASSWORD_RESET("비밀번호 재설정 메일", "재요청(API-010)");

    private final String label;
    private final String recovery;

    MailKind(String label, String recovery) {
        this.label = label;
        this.recovery = recovery;
    }

    String label() {
        return label;
    }

    String recovery() {
        return recovery;
    }
}
