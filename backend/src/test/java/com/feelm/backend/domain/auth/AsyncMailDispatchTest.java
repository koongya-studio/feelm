package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * 계정 메일 발송이 응답 경로에서 빠졌는지 검증한다(S15P21E106-282).
 *
 * <p>발송을 요청 스레드에서 기다리면 SMTP 왕복이 응답 시간에 실려, 계정이 있는 이메일과 없는 이메일의 202 응답 시간이 갈린다. 그러면 응답 본문·상태 코드로 감춘
 * 계정 존재 여부가 시간으로 드러난다(S15P21E106-151 리뷰 R-7).
 *
 * <p>시간을 재는 대신 발송 지점을 래치로 붙잡는다. 발송이 아직 끝나지 않은 상태에서 응답이 돌아오면 요청 스레드가 발송을 기다리지 않았다는 뜻이고, 동기 발송이면 이
 * 테스트는 응답을 받지 못해 실패한다. 시간 단정이 없으므로 실행 환경 속도에 흔들리지 않는다.
 */
@DisplayName("계정 메일 비동기 발송 — S15P21E106-282")
class AsyncMailDispatchTest extends EmailAuthApiTestSupport {

    @Test
    @DisplayName("가입 응답은 인증 메일 발송이 끝나기 전에 돌아온다")
    void signupDoesNotWaitForMailSend() throws Exception {
        TestAccount account = newAccount();

        verificationMails().blockSends();
        signup(signupBody(account)).andExpect(status().isCreated());

        assertThat(verificationMails().tokens()).as("응답 시점에는 발송이 끝나지 않았다").isEmpty();

        verificationMails().releaseSends();
        awaitMailsDispatched();
        assertThat(verificationMails().tokens()).as("발송은 응답 뒤에 끝난다").hasSize(1);
    }

    @Test
    @DisplayName("재발송 응답은 인증 메일 발송이 끝나기 전에 돌아온다")
    void resendDoesNotWaitForMailSend() throws Exception {
        TestAccount account = newAccount();
        signupPending(account);
        awaitMailsDispatched();
        verificationMails().clear();

        verificationMails().blockSends();
        resend(account.email()).andExpect(status().isAccepted());

        assertThat(verificationMails().tokens()).as("응답 시점에는 발송이 끝나지 않았다").isEmpty();

        verificationMails().releaseSends();
        awaitMailsDispatched();
        assertThat(verificationMails().tokens()).hasSize(1);
    }

    @Test
    @DisplayName("재설정 요청 응답은 재설정 메일 발송이 끝나기 전에 돌아온다")
    void passwordResetRequestDoesNotWaitForMailSend(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        passwordResetMails().clear();

        passwordResetMails().blockSends();
        requestPasswordReset(account.email()).andExpect(status().isAccepted());

        assertThat(passwordResetMails().tokens()).as("응답 시점에는 발송이 끝나지 않았다").isEmpty();

        passwordResetMails().releaseSends();
        awaitMailsDispatched();
        assertThat(passwordResetMails().tokens()).hasSize(1);
    }

    @Test
    @DisplayName("발송이 실패해도 응답은 202이고 다음 발송은 정상 처리된다")
    void mailSendFailureKeepsResponseAndNextSend(CapturedOutput output) throws Exception {
        TestAccount account = newAccount();
        signupActive(account, output);
        passwordResetMails().clear();

        passwordResetMails().failNextSend();
        requestPasswordReset(account.email()).andExpect(status().isAccepted());
        assertThat(passwordResetTokens()).as("실패한 발송은 기록되지 않는다").isEmpty();

        clearPasswordResetInterval(account.email());
        requestPasswordReset(account.email()).andExpect(status().isAccepted());

        assertThat(passwordResetTokens()).as("앞의 실패가 다음 발송을 막지 않는다").hasSize(1);
    }
}
