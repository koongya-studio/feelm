/*
 * 알림이 여는 화면 (업무 S15P21E106-610).
 *
 * 8.1 · 8.2의 알림은 아직 목업이다(인앱 알림 API-070~074의 프론트 연동은 S15P21E106-537).
 * 그전에는 목업 알림이 `targetId`(감상 기록 4004 · 초대 501)를 들고 실제 화면(2.4-A · 5.6)
 * 으로 갔고, 서버에 그 번호가 없어 **누르면 항상 실패 화면**이었다.
 *
 * 그래서 목업 알림은 번호를 갖지 않는다(`NotificationView.destination`). 갈 수 있는 곳은
 * 번호 없이 열리는 목록 화면뿐이고, 여기서 한 번에 경로로 바꾼다. 실제 알림이 연동되면
 * 계약의 `targetType` · `targetId`로 상세까지 가는 함수가 이 자리를 대신한다.
 */
import { path } from '../../routes/paths';
import type { NotificationDestination } from '../../types/unspecified';

export function notificationPath(destination: NotificationDestination): string {
  switch (destination) {
    case 'unrated':
      // 감상 확인은 7.1 MY의 `안 남긴 영화` 탭에서 실제 기록으로 이어진다
      return `${path.my()}?tab=unrated`;
    case 'party':
      // 받은 초대는 5.1 파티의 `받은 초대` 구역에서 연다(CR-27)
      return path.party();
    case 'recommendations':
      return path.recommendations();
    case 'film':
      return path.film();
  }
}
