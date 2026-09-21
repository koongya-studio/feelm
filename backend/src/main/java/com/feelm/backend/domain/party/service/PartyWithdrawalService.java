package com.feelm.backend.domain.party.service;

import com.feelm.backend.domain.party.model.PendingInvitationRef;
import com.feelm.backend.domain.party.repository.PartyMemberRepository;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 탈퇴한 사용자를 파티에서 떼어 내는 진입점이다(API-013). 계정 도메인은 파티 내부를 몰라도 이 메서드 하나만 부르면 된다.
 *
 * <p>계정 도메인이 {@link PartyService}를 직접 반복 호출하지 않고 이 지점을 두는 이유는 경계 때문이다(컨벤션 11장). 참여 파티를 찾는 질의, 파티마다의
 * 반복, 그 사이에 파티가 종료되는 경우의 처리는 모두 파티 도메인의 사정이다. 차단 관계를 같은 방식으로 정리하는 {@code
 * UserBlockService.deleteAllForWithdrawal}과 같은 모양이다.
 *
 * <p>규칙 자체는 다시 구현하지 않는다. 오너 승계, 마지막 멤버 종료, 대기 초대 무효화, 추천 갱신 요청은 파티 나가기(API-055)가 이미 하는 일이라 파티마다
 * {@link PartyService#leave}를 부른다. 두 경로가 갈라지면 한쪽만 고치는 일이 생긴다.
 *
 * <p>이 클래스에는 트랜잭션이 없다. 파티마다, 초대마다 아래 단위의 트랜잭션이 따로 열려야 한 곳의 경합이 다른 곳을 붙잡지 않고, 여러 파티의 행을 한 트랜잭션에 모아
 * 잠그지 않는다. 호출자도 계정 확정을 <b>커밋한 뒤에</b> 부른다 — 계정 잠금을 쥔 채 파티를 잠그지 않기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyWithdrawalService {

    private final PartyService partyService;
    private final PartyMemberRepository memberRepository;
    private final WithdrawnMemberInvitationWriter invitationWriter;

    /**
     * 참여 중인 모든 활성 파티에서 나가고 탈퇴자와 얽힌 대기 초대를 정리한다.
     *
     * <p><b>하나가 실패해도 나머지는 끝까지 처리한다.</b> 탈퇴는 한 번뿐이라 다시 부를 수 없고(두 번째 요청은 계정이 이미 {@code WITHDRAWN}이라
     * {@code UNAUTHORIZED}다), 중간에서 멈추면 남은 파티에 탈퇴한 계정이 멤버나 오너로 남아 사람이 SQL로 고치는 수밖에 없다(리뷰 B-3). 그래서 첫
     * 실패를 들고 끝까지 돈 뒤 마지막에 던진다 — 삼키지는 않는다. 실패를 감추면 남은 상태를 발견할 방법이 사라진다.
     *
     * <p>종료된 파티의 멤버십 행은 남긴다. 종료된 파티는 이력이고 파티 나가기(API-055)도 활성 파티만 대상으로 한다. 탈퇴해도 계정 행은 익명화된 채 남으므로
     * (D-9) 이력이 가리키는 곳이 사라지지도 않는다.
     */
    public void leaveAllForWithdrawal(long userId) {
        RuntimeException firstFailure = null;
        for (long partyId : memberRepository.findActivePartyIdsByUserId(userId)) {
            firstFailure = keepFirst(firstFailure, () -> leaveIfStillJoined(userId, partyId));
        }
        for (PendingInvitationRef ref : invitationWriter.findPendingInvitationsOf(userId)) {
            firstFailure =
                    keepFirst(firstFailure, () -> invitationWriter.declineIfStillPending(ref));
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /** 단계 하나를 실행하고, 실패하면 처음 실패만 들고 간다. 뒤이은 실패는 로그로 남긴다. */
    private RuntimeException keepFirst(RuntimeException firstFailure, Runnable step) {
        try {
            step.run();
            return firstFailure;
        } catch (RuntimeException exception) {
            if (firstFailure == null) {
                return exception;
            }
            log.warn("탈퇴 파티 정리 중 추가 실패", exception);
            return firstFailure;
        }
    }

    /**
     * 파티 하나에서 나간다. 목록을 읽은 뒤 그 파티가 먼저 종료됐거나 멤버십이 사라졌으면 할 일이 이미 끝난 것이므로 건너뛴다.
     *
     * <p>{@link PartyService#leave}가 이 두 코드를 <b>파티 상태와 멤버십</b>에만 쓴다는 전제에 기대고 있다. 그 메서드가 요청자의 계정
     * 상태까지 보게 되면 이미 {@code WITHDRAWN}인 탈퇴자의 정리가 통째로 조용히 건너뛰어진다(리뷰 A-2). 그래서 건너뛸 때마다 로그를 남겨, 정리가
     * 실제로는 아무 일도 하지 않는 상태가 되면 드러나게 한다.
     */
    private void leaveIfStillJoined(long userId, long partyId) {
        try {
            partyService.leave(userId, partyId);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.PARTY_CLOSED
                    && exception.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw exception;
            }
            log.warn(
                    "탈퇴 파티 정리를 건너뜀: userId={}, partyId={}, code={}",
                    userId,
                    partyId,
                    exception.getErrorCode());
        }
    }
}
