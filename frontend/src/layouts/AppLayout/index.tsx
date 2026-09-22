/*
 * 하단 내비가 있는 화면의 껍데기.
 * 적용 화면은 2.1 · 2.2 · 2.2-A · 3.1 · 3.2와 범위 밖 3탭이다 (REF-01 적용 화면).
 *
 * 화면 헤더는 화면마다 구성이 달라 각 화면이 ScreenHeader를 직접 렌더한다.
 *
 * 본문이 스크롤하는 곳이 여기 한 곳이라, 뒤로 가기로 돌아왔을 때 보던 자리를 되돌리는 것도
 * 여기서 한다(업무 -520, `useScrollRestoration`).
 *
 * ─────────────────────────── 데스크톱 셸 (업무 -782 · -787)
 *
 * 폭에 따라 껍데기를 둘 중 하나로 세운다. 기준점과 그 근거는 `styles/tokens.css`의
 * `데스크톱 셸` 주석에 있다.
 *
 * - 1024px 미만 — 아래 `모바일 셸`. **지금까지와 한 글자도 다르지 않다**
 * - 1024px 이상 — 좌측 고정 내비 · 상단 통합 검색 바 · 중앙 한 단(1280px 이상에서 우측 패널)
 *
 * 데스크톱 뼈대는 `layouts/DesktopShell`에 있다. -782에서는 이 파일이 직접 세웠지만,
 * -787에서 전체화면 화면도 같은 껍데기를 쓰게 되면서 밖으로 옮겼다 — 복사하면 둘이 따로
 * 자란다. 세우는 조건과 스크롤 위치 되돌리기는 여기 남는다.
 *
 * 폭이 바뀌면 껍데기가 통째로 갈린다. 본문 요소가 다른 요소로 바뀌므로 스크롤 위치는
 * 그때 한 번 맨 위로 돌아간다. 창 크기를 끌어 바꾸는 동안의 일이라 그대로 둔다.
 */
import { useRef } from 'react';
import { Outlet } from 'react-router';
import BottomNav from '../../components/BottomNav';
import { useScrollRestoration } from '../../hooks/useScrollRestoration';
import { useShellSize } from '../../hooks/useShellSize';
import DesktopShell from '../DesktopShell';
import styles from './AppLayout.module.css';

export default function AppLayout() {
  const bodyRef = useRef<HTMLDivElement>(null);
  const desktop = useShellSize() !== 'mobile';

  useScrollRestoration(bodyRef);

  if (desktop) {
    return (
      <DesktopShell bodyRef={bodyRef}>
        <Outlet />
      </DesktopShell>
    );
  }

  return (
    <div className={styles.layout} data-layout="app">
      {/* 모바일 본문은 `div` 그대로다. `main`으로 바꾸면 이 업무 밖에서 접근성 트리가 바뀐다 */}
      <div ref={bodyRef} className={styles.body}>
        <Outlet />
      </div>
      <BottomNav />
    </div>
  );
}
