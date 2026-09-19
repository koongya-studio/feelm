/*
 * 하단 내비게이션이 있는 화면의 껍데기.
 * 적용 화면은 2.1 · 2.2 · 2.2-A · 3.1 · 3.2와 범위 밖 3탭이다 (REF-01 적용 화면).
 *
 * 화면 헤더는 화면마다 구성이 달라 각 화면이 ScreenHeader를 직접 렌더한다.
 *
 * 본문이 스크롤하는 곳이 여기 한 곳이라, 뒤로 가기로 돌아왔을 때 보던 자리를 되돌리는 것도
 * 여기서 한다(업무 -520, `useScrollRestoration`).
 */
import { useRef } from 'react';
import { Outlet } from 'react-router';
import BottomNav from '../../components/BottomNav';
import { useScrollRestoration } from '../../hooks/useScrollRestoration';
import styles from './AppLayout.module.css';

export default function AppLayout() {
  const bodyRef = useRef<HTMLDivElement>(null);

  useScrollRestoration(bodyRef);

  return (
    <div className={styles.layout} data-layout="app">
      <div ref={bodyRef} className={styles.body}>
        <Outlet />
      </div>
      <BottomNav />
    </div>
  );
}
