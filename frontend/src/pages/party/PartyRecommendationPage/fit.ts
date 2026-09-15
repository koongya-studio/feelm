/*
 * 5.5의 `일치 94%` 계산.
 *
 * `[!]` **계약에 이 값이 없다.** API-058이 주는 것은 `reason.memberFit[]` — 사용자별
 * 점수(0~1)다. 와이어프레임은 카드마다 퍼센트 **하나**를 보여준다. 파티 추천이 "모두에게
 * 맞는 영화"를 고르는 일이므로 파티원 점수의 평균으로 읽었다.
 *
 * 최솟값(가장 안 맞는 사람 기준)으로 읽는 방법도 있다. 평균을 고른 이유는 와이어프레임의
 * 값(94 · 87 · 81)이 목업의 사용자별 점수 평균과 맞아떨어지기 때문이다. 계약이 대표값을
 * 정해 주면 이 파일은 사라진다.
 *
 * 화면 파일이 아니라 여기 두는 것은 화면 컴포넌트만 내보내야 하기 때문이다
 * (`react-refresh/only-export-components`).
 */
import type { PartyMemberFit } from '../../../types/party';

/** 파티원 점수의 평균을 퍼센트 정수로 바꾼다. 점수가 없으면 표시하지 않는다 */
export function fitPercent(memberFit: PartyMemberFit[]): number | null {
  if (memberFit.length === 0) return null;

  const total = memberFit.reduce((sum, member) => sum + member.score, 0);

  return Math.round((total / memberFit.length) * 100);
}
