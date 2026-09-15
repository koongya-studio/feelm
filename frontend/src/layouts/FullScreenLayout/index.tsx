/*
 * 전체화면 화면의 껍데기. 하단 내비게이션이 없다 (REF-01 미적용 화면).
 * 적용 화면은 2.4 · 2.4-A · 2.5 · 2.6 · 2.7 · 3.1-A · 5.8 · 5.9다.
 *
 * 뒤로 가기 요소는 화면마다 유무가 달라 각 화면의 ScreenHeader가 담당한다.
 */
import { Outlet } from 'react-router';
import styles from './FullScreenLayout.module.css';

export default function FullScreenLayout() {
  return (
    <div className={styles.layout} data-layout="fullscreen">
      <Outlet />
    </div>
  );
}
