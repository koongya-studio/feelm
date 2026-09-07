/*
 * 1.1 카드의 취향 값.
 *
 * 계약(API-024)이 저장하는 값은 `LIKE`와 `DISLIKE`뿐이다. 와이어프레임 범례에 있는
 * `안 봤어요`는 저장하지 않으므로 화면에서만 쓰는 값으로 둔다. `UNSET`도 같은 이유로
 * 화면 전용이다. 계약 타입(types/onboarding.ts)에는 넣지 않는다.
 */
import type { OnboardingPreferenceValue } from '../../../types/onboarding';

export type TasteValue = OnboardingPreferenceValue | 'UNWATCHED' | 'UNSET';

/** 와이어프레임 범례의 문구를 그대로 쓴다 */
export function tasteValueLabel(value: TasteValue): string {
  switch (value) {
    case 'LIKE':
      return '가까이';
    case 'DISLIKE':
      return '멀리';
    case 'UNWATCHED':
      return '안 봤어요';
    default:
      return '선택 안 함';
  }
}

/** 저장 대상인지. 명시적으로 선택한 LIKE · DISLIKE만 저장한다(API-024) */
export function isSavedValue(value: TasteValue): value is OnboardingPreferenceValue {
  return value === 'LIKE' || value === 'DISLIKE';
}
