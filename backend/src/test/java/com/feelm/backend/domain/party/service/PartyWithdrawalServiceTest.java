package com.feelm.backend.domain.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.domain.party.model.PendingInvitationRef;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 탈퇴 파티 정리의 실패 처리 규칙(S15P21E106-436 결정 D4).
 *
 * <p>계약 테스트로는 이 분기에 닿을 수 없다. 정리 대상 목록은 활성 파티만 담고, 그 목록을 읽은 직후 파티가 종료되는 경합은 HTTP 한 번으로 재현되지 않는다(리뷰
 * A-4). 그래서 파티 나가기를 대역으로 두고 규칙만 확인한다.
 */
@DisplayName("탈퇴 파티 정리의 실패 처리")
class PartyWithdrawalServiceTest {

    private final PartyService partyService = mock(PartyService.class);
    private final PartyMemberRepository memberRepository = mock(PartyMemberRepository.class);
    private final WithdrawnMemberInvitationWriter invitationWriter =
            mock(WithdrawnMemberInvitationWriter.class);
    private final PartyWithdrawalService service =
            new PartyWithdrawalService(partyService, memberRepository, invitationWriter);

    @Test
    @DisplayName("이미 종료됐거나 멤버가 아닌 파티는 건너뛰고 나머지를 계속 처리한다")
    void skipsRacedParties() {
        when(memberRepository.findActivePartyIdsByUserId(7L)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new BusinessException(ErrorCode.PARTY_CLOSED)).when(partyService).leave(7L, 1L);
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(partyService)
                .leave(7L, 2L);
        when(invitationWriter.findPendingInvitationsOf(7L)).thenReturn(List.of());

        service.leaveAllForWithdrawal(7L);

        verify(partyService).leave(7L, 3L);
    }

    @Test
    @DisplayName("그 밖의 실패는 나머지를 끝까지 처리한 뒤 전파한다")
    void finishesRemainingWorkBeforeRethrowing() {
        PendingInvitationRef ref = new PendingInvitationRef(9L, 90L, 30L);
        when(memberRepository.findActivePartyIdsByUserId(7L)).thenReturn(List.of(1L, 2L));
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR)).when(partyService).leave(7L, 1L);
        when(invitationWriter.findPendingInvitationsOf(7L)).thenReturn(List.of(ref));

        // 첫 파티에서 멈추면 남은 파티와 초대에 탈퇴자가 그대로 남는다. 탈퇴는 다시 부를 수 없다(리뷰 B-3).
        assertThatThrownBy(() -> service.leaveAllForWithdrawal(7L))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        thrown ->
                                assertThat(((BusinessException) thrown).getErrorCode())
                                        .isEqualTo(ErrorCode.INTERNAL_ERROR));

        verify(partyService).leave(7L, 2L);
        verify(invitationWriter).declineIfStillPending(ref);
    }

    @Test
    @DisplayName("초대 정리가 실패해도 처음 실패를 던진다")
    void keepsFirstFailure() {
        PendingInvitationRef first = new PendingInvitationRef(9L, 90L, 30L);
        PendingInvitationRef second = new PendingInvitationRef(9L, 91L, 40L);
        when(memberRepository.findActivePartyIdsByUserId(7L)).thenReturn(List.of());
        when(invitationWriter.findPendingInvitationsOf(7L)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("먼저"))
                .when(invitationWriter)
                .declineIfStillPending(first);
        doThrow(new IllegalStateException("나중"))
                .when(invitationWriter)
                .declineIfStillPending(second);

        assertThatThrownBy(() -> service.leaveAllForWithdrawal(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("먼저");

        // 두 번째 초대도 시도해야 한다. 첫 실패에서 멈추면 남은 초대가 그대로 대기 상태로 남는다.
        verify(invitationWriter, times(2)).declineIfStillPending(any(PendingInvitationRef.class));
    }
}
