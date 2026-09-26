/* 알림이 여는 화면 (업무 S15P21E106-537). */
import { getMyInvitations, getParty } from '../../api/parties';
import { path } from '../../routes/paths';
import type { NotificationItem } from '../../types/notification';

export function notificationPath(item: NotificationItem): string {
  switch (item.targetType) {
    case 'WATCH_RECORD':
      return path.watchConfirm(item.targetId);
    case 'PARTY_INVITATION':
      return path.partyInvitation(item.targetId);
    case 'PARTY':
      // 별도 파티 홈 경로가 없어 파티 카드가 여는 파티 상세(파티원 팝콘)로 연결한다.
      return path.partyPopcorn(item.targetId);
    case 'TASTE_REPORT':
      // 3.3 새 리포트 발급. 이미 연 리포트여도 첫 발급 기록(API-080)이 멱등이라 다시 거쳐도 된다
      return path.reportIssued(item.targetId);
  }
}

/**
 * 알림이 가리키는 곳으로 갈 수 없는 까닭. 갈 수 있으면 `null`이다(업무 S15P21E106-866).
 *
 * 파티 초대 수락 알림(`PARTY`)은 5.8 파티원 팝콘을 여는데, 알림은 그 뒤에도 목록에 남는다.
 * 그사이 파티가 끝났거나 내가 나갔으면(내보내졌으면) 5.8은 `파티 정보를 불러오지 못했어요 ·
 * 주소가 잘못되었거나…`라는 일반 오류만 보였다. 이동하기 전에 내 파티 목록(API-050)에서
 * 찾아보고, 못 가면 머문 채 까닭을 말한다.
 *
 * 목록을 못 받으면 가려내지 못한 것이지 갈 수 없는 것이 아니다 — 그때는 이동하고 5.8이
 * 제 오류를 보인다. 초대 알림은 API-052의 응답 이력과 현재 파티 상태를 대조한다.
 * 이력이 없다는 이유만으로 파티가 종료됐다고 판단하지 않는다.
 */
export async function unavailableReason(
  item: NotificationItem,
): Promise<{ message: string; partyPath?: string } | null> {
  if (item.targetType === 'PARTY_INVITATION') {
    try {
      const pending = await getMyInvitations();
      if (pending.some((invitation) => invitation.invitationId === item.targetId)) return null;

      const accepted = await getMyInvitations('ACCEPTED');
      const invitation = accepted.find((value) => value.invitationId === item.targetId);
      if (invitation) {
        const party = await getParty(invitation.party.partyId, { includeClosed: true });
        if (party === null) return { message: '나갔거나 내보내진 파티예요.' };
        return party.status === 'ACTIVE'
          ? { message: '이미 참여 중인 파티예요.', partyPath: path.partyPopcorn(party.partyId) }
          : { message: '이미 끝난 파티예요.' };
      }

      const declined = await getMyInvitations('DECLINED');
      if (declined.some((value) => value.invitationId === item.targetId)) {
        return { message: '이미 거절한 초대예요.' };
      }
      return { message: '초대가 만료됐거나 더 이상 유효하지 않아요.' };
    } catch {
      return { message: '초대 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.' };
    }
  }
  if (item.targetType !== 'PARTY') return null;

  let party: Awaited<ReturnType<typeof getParty>>;

  try {
    party = await getParty(item.targetId, { includeClosed: true });
  } catch {
    return null;
  }

  if (party === null) return { message: '나갔거나 내보내진 파티예요.' };
  if (party.status === 'CLOSED') return { message: '이미 끝난 파티예요.' };

  return null;
}
