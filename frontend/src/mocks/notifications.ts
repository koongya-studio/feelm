/*
 * 앱 내 알림 목업 (8.1 · 8.2). **계약에 알림 목록이 없다**(types/unspecified.ts).
 *
 * 문구와 순서는 와이어프레임 8.1 그대로다. 누르면 가는 곳에 필요한 번호만 맞췄다.
 *
 * - 감상 확인(4004) — 세션 저장소가 시작할 때 넣는 `LINK_CLICKED` 기록이다(mocks/watch.ts).
 *   2.4-A가 목록(API-034)에서 번호로 찾으므로 실제로 있어야 한다
 * - 파티 초대(501) — mocks/partyInvitations.ts의 대기 중인 초대. 5.6이 번호로 찾는다
 *
 * `[?]` 와이어프레임의 둘째 알림은 `파티 모드 초대 수락 · 무비버디 님이 파티 모드에
 * 참여했어요`인데 이동표는 `8.1 → 5.6 파티 초대 알림 선택`이다. 5.6은 **내가 받은 초대**를
 * 보는 화면이라 문구와 목적지가 어긋난다. 목적지를 지키고 문구를 초대받은 쪽으로 바꿨다.
 * `[?]` 《나우 유 씨 미》 · 《헤어질 결심》은 실제 제목이다. 영화 목업은 자리표시자라
 * 목적지 영화의 제목을 쓴다.
 *
 * 시각은 세션이 시작된 때를 기준으로 계산한다. 와이어프레임이 `방금 전 · 30분 전 · 2시간 전 ·
 * 어제`를 그렸기 때문이다.
 */
import { findMockMovie } from './movies';
import type { NotificationView } from '../types/unspecified';

const MINUTE = 60 * 1000;

export function createSeedNotifications(now: Date = new Date()): NotificationView[] {
  const ago = (ms: number) => new Date(now.getTime() - ms).toISOString();

  return [
    {
      notificationId: 7004,
      type: 'WATCH_CONFIRM',
      title: '영화 잘 보셨나요?',
      body: `《${findMockMovie(104).title}》 감상 확인하러 가기`,
      createdAt: ago(0),
      read: false,
      targetId: 4004,
    },
    {
      notificationId: 7003,
      type: 'PARTY_INVITE',
      title: '파티 모드 초대',
      body: '무비버디 님이 파티 모드에 초대했어요',
      createdAt: ago(30 * MINUTE),
      read: false,
      targetId: 501,
    },
    {
      notificationId: 7002,
      type: 'RECOMMENDATION',
      title: '오늘의 추천이 도착했어요',
      body: '취향 맞춤 2편 · 새로운 맛 1편',
      createdAt: ago(120 * MINUTE),
      read: true,
      targetId: null,
    },
    {
      notificationId: 7001,
      type: 'FRAME_ADDED',
      title: '필름에 새 프레임이 감겼어요',
      body: `《${findMockMovie(401).title}》이 취향 필름에 추가됐어요`,
      createdAt: ago(26 * 60 * MINUTE),
      read: true,
      targetId: null,
    },
  ];
}
