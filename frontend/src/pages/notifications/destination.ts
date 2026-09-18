/* 알림이 여는 화면 (업무 S15P21E106-537). */
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
  }
}
