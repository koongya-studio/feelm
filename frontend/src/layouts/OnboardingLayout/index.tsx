/*
 * 초기 취향 설정 화면의 껍데기. 하단 내비게이션이 없다 (REF-01 미적용 화면).
 * 적용 화면은 1.1 · 1.2 · 1.3이다.
 *
 * 화면 간 선택값 유지를 위한 OnboardingContext는 주요 사용자 흐름 및
 * 화면 간 상태 전달 업무(S15P21E106-263)에서 추가한다.
 */
import { Outlet } from 'react-router';
import styles from './OnboardingLayout.module.css';

export default function OnboardingLayout() {
  return (
    <div className={styles.layout} data-layout="onboarding">
      <Outlet />
    </div>
  );
}
