/*
 * 온보딩 진행 상태 Provider.
 *
 * 1.1 → 1.2 → 1.3이 순차로 값을 쌓아 가므로 Context로 전달한다
 * (frontend-structure.md 6.2 · 6.4). `OnboardingLayout` 안에서만 제공하며 온보딩을
 * 벗어나면 사라진다.
 *
 * 이 Context는 화면 간 상태 전달 업무(S15P21E106-263)에서 만들 예정이었으나,
 * 1.1 ~ 1.3을 구현하려면 지금 필요하므로 이 업무에서 만들었다.
 *
 * `[?]` 온보딩 도중 앱을 닫았을 때 진행값을 유지할지는 미확정이다. 유지하지 않으므로
 * 새로 고침하면 초기화되고, 1.2 · 1.3은 1.1로 되돌린다(frontend-structure.md 6.3).
 *
 * **파일 이름이 `OnboardingContext.tsx`이면 Windows에서 앱이 뜨지 않는다.** 같은 폴더의
 * `onboardingContext.ts`와 대소문자만 다르면, 확장자 없는 import를 해석할 때 `.ts`를
 * 먼저 찾는 규칙과 대소문자를 구분하지 않는 파일 시스템이 겹쳐 상태 파일로 잘못
 * 이어진다(업무 -265에서 확인). 그래서 이 파일은 `OnboardingProvider.tsx`다.
 */
import { useCallback, useMemo, useState } from 'react';
import { OnboardingContext, initialOnboardingState } from './onboardingContext';
import type { ReactNode } from 'react';
import type { OnboardingState } from './onboardingContext';
import type { OnboardingPreference } from '../types/onboarding';

export function OnboardingProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<OnboardingState>(initialOnboardingState);

  const completeTaste = useCallback((preferences: OnboardingPreference[], skipped: boolean) => {
    setState((previous) => ({
      ...previous,
      preferences: skipped ? [] : preferences,
      skipped,
      tasteSubmitted: true,
    }));
  }, []);

  const completeOtt = useCallback((ottIds: number[]) => {
    setState((previous) => ({ ...previous, ottIds, ottSubmitted: true }));
  }, []);

  const value = useMemo(
    () => ({ ...state, completeTaste, completeOtt }),
    [state, completeTaste, completeOtt],
  );

  return <OnboardingContext.Provider value={value}>{children}</OnboardingContext.Provider>;
}
