package com.feelm.backend.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.auth.entity.EmailVerificationToken;
import com.feelm.backend.domain.auth.entity.PasswordResetToken;
import com.feelm.backend.domain.auth.repository.EmailVerificationTokenRepository;
import com.feelm.backend.domain.auth.repository.PasswordResetTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 두 1회용 토큰 발급기가 공통 골격을 쓰면서도 종류별 성질을 유지하는지 검증한다(S15P21E106-284).
 *
 * <p>계약 테스트는 발급된 토큰으로 인증·재설정이 되는지까지만 보므로, 유효기간이 24시간에서 30분으로(또는 반대로) 뒤바뀌어도 통과한다. 만료 시각과 무효화 호출은 저장
 * 지점에서만 관찰할 수 있어 리포지토리 대역으로 확인한다.
 *
 * <p>재설정 발급기의 사용자 단위 직렬화(283 리뷰 발견 1)는 락 획득 자체를 관찰할 수 없으므로 <b>트랜잭션 동기화 등록</b>으로 본다. 그 동기화가 락을 커밋·롤백
 * 시점까지 붙잡아 두는 장치이고, 공통화 과정에서 이 호출이 조용히 사라지면 여러 요청이 같은 사용자의 토큰을 동시에 회전시킬 수 있다.
 */
@DisplayName("1회용 토큰 발급 — 공통 골격과 종류별 성질")
class OneTimeTokenIssuerTest {

    private static final long USER_ID = 42L;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("인증 토큰은 24시간 뒤에 만료된다")
    void verificationTokenExpiresInOneDay() {
        EmailVerificationTokenRepository repository = mock(EmailVerificationTokenRepository.class);
        when(repository.findAllByUserIdAndUsedAtIsNull(USER_ID)).thenReturn(List.of());
        VerificationTokenIssuer issuer = new VerificationTokenIssuer(repository);

        Instant before = Instant.now();
        issuer.issue(USER_ID);

        ArgumentCaptor<EmailVerificationToken> saved =
                ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt())
                .isBetween(
                        before.plus(Duration.ofHours(24)),
                        Instant.now().plus(Duration.ofHours(24)));
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("재설정 토큰은 30분 뒤에 만료된다")
    void passwordResetTokenExpiresInThirtyMinutes() {
        PasswordResetTokenRepository repository = mock(PasswordResetTokenRepository.class);
        when(repository.findAllByUserIdAndUsedAtIsNull(USER_ID)).thenReturn(List.of());
        PasswordResetTokenIssuer issuer = new PasswordResetTokenIssuer(repository);
        TransactionSynchronizationManager.initSynchronization();

        Instant before = Instant.now();
        issuer.issue(USER_ID);

        ArgumentCaptor<PasswordResetToken> saved =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt())
                .isBetween(
                        before.plus(Duration.ofMinutes(30)),
                        Instant.now().plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("발급은 원문을 돌려주고 저장은 그 원문의 해시만 남긴다")
    void issuedTokenIsStoredAsHash() {
        EmailVerificationTokenRepository repository = mock(EmailVerificationTokenRepository.class);
        when(repository.findAllByUserIdAndUsedAtIsNull(USER_ID)).thenReturn(List.of());
        VerificationTokenIssuer issuer = new VerificationTokenIssuer(repository);

        String rawToken = issuer.issue(USER_ID);

        ArgumentCaptor<EmailVerificationToken> saved =
                ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(repository).save(saved.capture());
        assertThat(rawToken).isNotBlank();
        assertThat(saved.getValue().getTokenHash())
                .isEqualTo(issuer.hashOf(rawToken))
                .isNotEqualTo(rawToken)
                .hasSize(64);
    }

    @Test
    @DisplayName("재발급은 기존 미사용 토큰을 모두 소진 처리한다")
    void issueMarksPreviousTokensUsed() {
        EmailVerificationTokenRepository repository = mock(EmailVerificationTokenRepository.class);
        EmailVerificationToken previous =
                EmailVerificationToken.issue(USER_ID, "hash", Instant.now().plusSeconds(60));
        when(repository.findAllByUserIdAndUsedAtIsNull(USER_ID)).thenReturn(List.of(previous));
        VerificationTokenIssuer issuer = new VerificationTokenIssuer(repository);

        issuer.issue(USER_ID);

        assertThat(previous.isUsed()).isTrue();
    }

    @Test
    @DisplayName("재설정 발급기는 무효화 전에 트랜잭션 동기화를 등록해 락을 커밋까지 붙잡는다")
    void passwordResetIssuerRegistersSynchronization() {
        PasswordResetTokenRepository repository = mock(PasswordResetTokenRepository.class);
        when(repository.findAllByUserIdAndUsedAtIsNull(USER_ID)).thenReturn(List.of());
        PasswordResetTokenIssuer issuer = new PasswordResetTokenIssuer(repository);
        TransactionSynchronizationManager.initSynchronization();

        issuer.issue(USER_ID);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    @DisplayName("새 토큰 없이 무효화만 하는 경로도 같은 직렬화를 쓴다")
    void invalidateAllUnusedRegistersSynchronization() {
        PasswordResetTokenRepository repository = mock(PasswordResetTokenRepository.class);
        when(repository.findAllByUserIdAndUsedAtIsNull(USER_ID)).thenReturn(List.of());
        PasswordResetTokenIssuer issuer = new PasswordResetTokenIssuer(repository);
        TransactionSynchronizationManager.initSynchronization();

        issuer.invalidateAllUnused(USER_ID);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("인증 발급기는 트랜잭션 동기화를 등록하지 않는다")
    void verificationIssuerDoesNotSerializePerUser() {
        EmailVerificationTokenRepository repository = mock(EmailVerificationTokenRepository.class);
        when(repository.findAllByUserIdAndUsedAtIsNull(USER_ID)).thenReturn(List.of());
        VerificationTokenIssuer issuer = new VerificationTokenIssuer(repository);
        TransactionSynchronizationManager.initSynchronization();

        issuer.issue(USER_ID);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }
}
