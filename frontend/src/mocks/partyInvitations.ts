/*
 * 파티 초대 목업. API-052 받은 초대 목록
 *
 * 와이어프레임 5.6이 `무비버디 님의 파티` · `2인 · 오늘 저녁 같이 볼 영화`를 보여주므로
 * 그 값을 옮겼다. 파티 이름이 `오늘 저녁 같이 볼 영화`이고 파티장이 `무비버디`다.
 *
 * `[?]` 5.6의 `2인`은 **나까지 세었을 때의 파티 인원**으로 읽었다. API-052가 주는
 * `memberCount`는 지금 참여 중인 사람 수(아직 나는 없다)이므로 화면은 여기에 1을 더한다.
 * 그래야 수락한 뒤의 5.7 `김난다 × 무비버디`(두 사람)와 앞뒤가 맞는다. `memberCount`를
 * 그대로 `2인`으로 읽으면 수락 후 세 명이 되어 5.7과 어긋난다.
 */
import type { PartyInvitation } from '../types/party';

export const mockPartyInvitations: PartyInvitation[] = [
  {
    invitationId: 501,
    party: {
      partyId: 9002,
      name: '오늘 저녁 같이 볼 영화',
      owner: { userId: 2048, nickname: '무비버디' },
      memberCount: 1,
    },
    status: 'PENDING',
    createdAt: '2026-09-10T09:00:00Z',
  },
];
