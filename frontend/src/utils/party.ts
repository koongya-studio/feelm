/*
 * 파티 팝콘 계산.
 *
 * 5.9 팝콘 섞기와 5.5 추천 완성이 같은 통을 그린다(사용자 시안 2026-09-17, 업무 -462).
 * 두 화면이 쓰게 되어 화면 폴더에서 옮겼다.
 */
import type { FlavorCount } from '../components/PopcornBucket/kernels';
import type { PartyPopcorn } from '../types/party';

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
