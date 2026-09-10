/*
 * 5.7 `OOO 님이 초대를 수락했어요!`의 그 사람을 고르는 규칙.
 *
 * `[?]` **계약에 없다.** API-053은 수락한 본인에게 응답하고, 초대한 사람에게는 파티
 * 정보가 폴링으로 갱신된다고만 적혀 있다. 누가 방금 들어왔는지 알려 주는 필드가 없다.
 * API-050 파티원 중 **나를 뺀 마지막 사람**을 방금 들어온 사람으로 읽는다.
 *
 * 계약이 없으니 이 규칙은 내가 정한 것이다. 그런 것일수록 고정해 두어야 계약이 생겼을 때
 * 무엇을 바꿔야 하는지 드러난다(`joinedMember.test.ts`).
 *
 * 화면 파일이 아니라 여기 두는 것은 화면 컴포넌트만 내보내야 하기 때문이다
 * (`react-refresh/only-export-components`).
 */
import type { PartyMember } from '../../../types/party';

/**
 * 방금 들어온 것으로 볼 사람. 나뿐이면 `null`이다.
 *
 * `members` 순서는 API-050이 준 그대로 쓴다. 목업과 계약 모두 파티장이 앞이고 나중에
 * 들어온 사람이 뒤다.
 */
export function findJoinedMember(members: PartyMember[], myUserId: number): PartyMember | null {
  const others = members.filter((member) => member.userId !== myUserId);

  return others[others.length - 1] ?? null;
}
