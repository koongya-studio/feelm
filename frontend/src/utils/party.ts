/*
 * 파티 팝콘 계산과 파티 상태 문구.
 *
 * 5.9 팝콘 섞기와 5.5 추천 완성이 같은 통을 그린다(사용자 시안 2026-09-17, 업무 -462).
 * 두 화면이 쓰게 되어 화면 폴더에서 옮겼다.
 */
import { PARTY_MIN_MEMBERS } from '../api/parties';
import type { FlavorCount } from '../components/PopcornBucket/kernels';
import type { Party, PartyPopcorn } from '../types/party';

/**
 * 파티 목록 카드의 상태 문구. 모바일 대표 카드와 데스크톱 카드가 함께 쓴다(업무 -872).
 *
 * 계약의 파티 상태는 `ACTIVE` · `CLOSED`뿐이고 목록에 오는 것은 활성 파티뿐이라, 그 안을
 * **추천을 받을 수 있는지**로 가른다 — 파티원이 `PARTY_MIN_MEMBERS`(2명) 이상이면
 * `진행 중`, 방장 혼자면 초대를 기다리는 `모집 중`.
 *
 * 전에는 정원으로 갈랐다(사용자 결정 2026-09-22). 그런데 정원은 아무 기능도 막지 않아
 * 2/4명처럼 이미 추천을 받는 파티가 `모집 중`으로 읽혔고, 모바일만 늘 `진행 중`이라 같은
 * 파티가 두 화면에서 다르게 보였다. 사용자 결정 2026-09-25로 기준을 바꿨다.
 */
export function partyStatusLabel(party: Pick<Party, 'members'>): string {
  return party.members.length >= PARTY_MIN_MEMBERS ? '진행 중' : '모집 중';
}

/**
 * 파티원 전원의 맛을 한 통에 합친다.
 *
 * 맛별 합계만 내면 된다. **비율을 유지하며 알 수를 줄이는 일은 통이 한다**(업무 -577).
 * 전에는 화면에서 100알로 나누고 잔여를 채웠는데, `PopcornBucket`이 같은 요약을 다시
 * 하게 되면서 두 번 나누는 셈이 됐다.
 */
export function mixedFlavors(popcorn: PartyPopcorn): FlavorCount[] {
  const totals = new Map<string, number>();

  for (const member of popcorn.members) {
    for (const flavor of member.flavors) {
      totals.set(flavor.flavorCode, (totals.get(flavor.flavorCode) ?? 0) + flavor.popcornCount);
    }
  }

  return [...totals.entries()].map(([code, count]) => ({ code, count }));
}
