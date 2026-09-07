/*
 * 하단 내비게이션이 있는 화면의 껍데기.
 * 적용 화면은 2.1 · 2.2 · 2.2-A · 3.1 · 3.2와 범위 밖 3탭이다 (REF-01 적용 화면).
 *
 * 화면 헤더는 화면마다 구성이 달라 각 화면이 ScreenHeader를 직접 렌더한다.
 */
import { Outlet } from 'react-router';
import BottomNav from '../../components/BottomNav';
import styles from './AppLayout.module.css';

export default function AppLayout() {
  return (
    <div className={styles.layout} data-layout="app">
      <div className={styles.body}>
        <Outlet />
      </div>
      <BottomNav />
    </div>
  );
}
