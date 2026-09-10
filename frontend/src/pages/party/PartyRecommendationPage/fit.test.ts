/*
 * 5.5 `일치 94%` 계산 (업무 S15P21E106-304)
 *
 * 계약에 없는 값을 화면이 만들어 내는 자리다(`fit.ts` 머리말). 계약이 대표값을 정해 주면
 * 이 계산은 사라지지만, 그때까지는 와이어프레임의 숫자와 맞아야 한다.
 */
import { describe, expect, it } from 'vitest';
import { fitPercent } from './fit';
import { mockPartyRecommendations } from '../../../mocks/partyRecommendations';

describe('fitPercent', () => {
  it('파티원 점수의 평균을 퍼센트로 바꾼다', () => {
    expect(
      fitPercent([
        { userId: 1, score: 0.96 },
        { userId: 2, score: 0.94 },
        { userId: 3, score: 0.92 },
      ]),
    ).toBe(94);
  });

  it('점수가 없으면 표시하지 않는다', () => {
    expect(fitPercent([])).toBeNull();
  });

  it('목업 세 편이 와이어프레임 5.5의 94 · 87 · 81과 맞는다', () => {
    const percents = mockPartyRecommendations[9001].items.map((item) =>
      fitPercent(item.reason.memberFit),
    );

    expect(percents).toEqual([94, 87, 81]);
  });
});
