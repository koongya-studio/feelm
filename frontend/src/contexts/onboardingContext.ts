/*
 * 온보딩 Context 정의와 조회 훅.
 *
 * Provider는 OnboardingContext.tsx에 둔다. 한 파일이 컴포넌트와 그 밖의 값을 함께
 * export하면 Fast Refresh가 동작하지 않아(react-refresh/only-export-components)
 * 컴포넌트가 아닌 것만 이 파일에 모았다.
 */
import { createContext, useContext } from 'react';
import type { OnboardingPreference } from '../types/onboarding';

export type OnboardingState = {
  /** 1.1에서 저장한 취향 목록. 건너뛰면 빈 배열 */
  preferences: OnboardingPreference[];
  /** 1.1을 건너뛰었는지 (API-024 `skipped`) */
  skipped: boolean;
  /** 1.1을 마쳤는지. 1.2 진입 조건 */
  tasteSubmitted: boolean;
  /** 1.2에서 선택한 OTT id 목록 */
  ottIds: number[];
  /** 1.2를 마쳤는지. 1.3 진입 조건 */
  ottSubmitted: boolean;
};

export type OnboardingValue = OnboardingState & {
  completeTaste: (preferences: OnboardingPreference[], skipped: boolean) => void;
  completeOtt: (ottIds: number[]) => void;
};

export const initialOnboardingState: OnboardingState = {
  preferences: [],
  skipped: false,
  tasteSubmitted: false,
  ottIds: [],
  ottSubmitted: false,
};

export const OnboardingContext = createContext<OnboardingValue | null>(null);

export function useOnboarding(): OnboardingValue {
  const value = useContext(OnboardingContext);

  if (!value) {
    throw new Error('useOnboarding은 OnboardingLayout 안에서만 사용할 수 있습니다.');
  }

  return value;
}
