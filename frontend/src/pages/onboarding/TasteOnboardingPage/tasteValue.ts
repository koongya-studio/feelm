/*
 * 1.1 카드의 취향 값.
 *
 * 계약(API-024)이 저장하는 값은 `LIKE`와 `DISLIKE`뿐이다. 처음 받은 카드는 원 밖의
 * `DISLIKE`로 시작하고, 보지 않은 영화는 `⊖`로 목록에서 제거한다.
 */
import type { OnboardingPreferenceValue } from '../../../types/onboarding';

export type TasteValue = OnboardingPreferenceValue;

/** 와이어프레임 범례의 문구를 그대로 쓴다 */
export function tasteValueLabel(value: TasteValue): string {
  switch (value) {
    case 'LIKE':
      return '가까이';
    case 'DISLIKE':
      return '멀리';
  }
}
