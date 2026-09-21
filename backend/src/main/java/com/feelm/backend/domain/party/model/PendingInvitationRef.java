package com.feelm.backend.domain.party.model;

/**
 * 정리 대상 대기 초대의 식별자 묶음(API-013).
 *
 * <p>Entity가 아니라 식별자만 읽는 이유는 잠금 때문이다. 탈퇴 정리는 초대 행을 고치기 전에 그 초대가 속한 파티부터 잠가야 하는데(저장소의 <b>파티 → 초대</b>
 * 잠금 순서), 잠그기 전에 읽은 Entity는 잠근 뒤의 상태와 다를 수 있어 그대로 쓰면 안 된다. 여기서는 "무엇을 다시 볼지"만 정하고 실제 판정은 잠금 뒤에 한다.
 */
public record PendingInvitationRef(long partyId, long invitationId, long inviteeId) {}
