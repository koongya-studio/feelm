/*
 * 초기 취향 설정 화면의 껍데기. 하단 내비게이션이 없다 (REF-01 미적용 화면).
 * 적용 화면은 1.1 · 1.2 · 1.3이다.
 *
 * 화면 간 선택값은 OnboardingContext로 전달한다(frontend-structure.md 6.4 —
 * 제공 위치는 이 레이아웃, 소멸 시점은 레이아웃 이탈).
 */
import { Outlet } from 'react-router';
import { OnboardingProvider } from '../../contexts/OnboardingProvider';
import styles from './OnboardingLayout.module.css';

export default function OnboardingLayout() {
  return (
    <OnboardingProvider>
      <div className={styles.layout} data-layout="onboarding">
        <Outlet />
      </div>
    </OnboardingProvider>
  );
}
