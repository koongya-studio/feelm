package com.feelm.backend.infrastructure.mail;

/**
 * 계정 메일의 종류다. 발송이 요청 스레드를 떠나면서 실패·유실 기록도 발송 지점이 아니라 {@link MailDispatcher}에서 남게 되어, 종류마다 다른 문구와 사용자
 * 복구 경로를 이 열거형이 들고 있는다.
 *
 * <p>복구 경로를 값으로 둔 이유는 발송 실패 로그가 "무엇이 실패했고 사용자가 무엇으로 되돌릴 수 있는지"를 함께 말해야 하기 때문이다.
 *
 * <p>제목과 본문도 여기에 있다(S15P21E106-284). 계정 메일의 발송 인터페이스와 구현이 종류별로 미러링돼 있던 것을 하나로 합치면서, 종류마다 실제로 달라지는
 * 값을 이 열거형에 모았다. 그래서 <b>메일 종류가 늘면 인터페이스·구현체·조건부 빈·테스트 스파이가 아니라 이 열거형에 값을 추가한다.</b> 본문의 유효기간 문구는
 * 발급기의 TTL 상수({@code VerificationTokenIssuer}는 24시간, {@code PasswordResetTokenIssuer}는 30분, {@code
 * EmailVerificationCodeStore}는 10분)와 짝이므로 한쪽을 바꾸면 다른 쪽도 바꾼다 — TTL 자체를 여기로 옮기지 않은 것은 유효기간이 도메인
 * 규칙이라서다.
 */
public enum MailKind {
    /** 가입 인증 메일(API-001·API-003). */
    VERIFICATION(
            "인증 메일",
            "재발송(API-003)",
            "[Feelm] 이메일 인증을 완료해 주세요",
            """
            Feelm 가입을 확인해 주세요.

            아래 링크를 열면 이메일 인증이 완료됩니다. 링크는 24시간 동안만 유효합니다.
            %s

            본인이 요청하지 않았다면 이 메일을 무시해 주세요."""),

    /**
     * 가입 인증번호 메일(API-078). 계정을 만들기 전에 보내므로 수신자에게 아직 사용자 식별자가 없다.
     *
     * <p>링크가 아니라 6자리 번호를 싣는 첫 메일이다. 유효기간 문구는 인증번호의 TTL({@code EmailVerificationCodeStore})과 짝이므로
     * 한쪽을 바꾸면 다른 쪽도 바꾼다.
     */
    VERIFICATION_CODE(
            "인증번호 메일",
            "인증번호 재요청(API-078)",
            "[Feelm] 이메일 인증번호를 입력해 주세요",
            """
            Feelm 가입을 확인해 주세요.

            아래 인증번호를 회원가입 화면에 입력하면 이메일 인증이 완료됩니다. 번호는 10분 동안만 유효합니다.
            %s

            본인이 요청하지 않았다면 이 메일을 무시해 주세요."""),

    /** 비밀번호 재설정 메일(API-010). */
    PASSWORD_RESET(
            "비밀번호 재설정 메일",
            "재요청(API-010)",
            "[Feelm] 비밀번호 재설정 안내",
            """
            Feelm 비밀번호 재설정을 요청하셨습니다.

            아래 링크에서 새 비밀번호를 설정할 수 있습니다. 링크는 30분 동안만 유효하며 한 번만 사용할 수 있습니다.
            %s

            본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 변경되지 않습니다.""");

    private final String label;
    private final String recovery;
    private final String subject;
    private final String bodyTemplate;

    MailKind(String label, String recovery, String subject, String bodyTemplate) {
        this.label = label;
        this.recovery = recovery;
        this.subject = subject;
        this.bodyTemplate = bodyTemplate;
    }

    String label() {
        return label;
    }

    String recovery() {
        return recovery;
    }

    String subject() {
        return subject;
    }

    /** 본문에 끼울 값을 넣은 완성 본문. 값은 {@code AuthMailProperties}가 종류에 맞게 만든다(링크 또는 인증번호). */
    String body(String content) {
        return bodyTemplate.formatted(content);
    }
}
