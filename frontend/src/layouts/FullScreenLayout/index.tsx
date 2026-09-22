/*
 * 전체화면 화면의 껍데기. 하단 내비가 없다 (REF-01 미적용 화면).
 * 적용 화면은 2.4 · 2.4-A · 2.5 · 2.6 · 2.7 · 3.1-A · 5.8 · 5.9다.
 *
 * 뒤로 가기 요소는 화면마다 유무가 달라 각 화면의 ScreenHeader가 담당한다.
 *
 * ─────────────────────────── 데스크톱 셸 (업무 -787)
 *
 * **1024px 이상에서는 탭 화면과 같은 껍데기를 쓴다**(사용자 결정 2026-09-22). -782가 탭
 * 화면에만 데스크톱 셸을 세운 탓에, 넓은 화면에서 3.1 필름을 보다가 2.4 영화 상세로 들어가면
 * 좌측 내비가 사라지고 폭이 412px로 좁아졌다가 뒤로 가면 되돌아왔다. 들를 때마다 앱의 뼈대가
 * 바뀌는 셈이라 그 불연속을 없앤다.
 *
 * 1024px 미만은 아래 모바일 껍데기 그대로다. 한 글자도 바꾸지 않았다.
 *
 * **로그인한 사람에게만 셸을 세운다.** 이 레이아웃에는 로그인 없이 닿을 수 있는 화면이
 * 하나 있다 — 없는 주소(404)다(`routes/router.tsx`에서 `RequireAuth` 바깥에 있다). 좌측
 * 내비는 로그인해야 갈 수 있는 곳만 가리키므로, 로그인하지 않은 사람에게 세우면 누를 때마다
 * 로그인 화면으로 튕기는 메뉴를 보여주게 된다.
 *
 * ─────────────────────────── 스크롤
 *
 * 모바일에서는 문서가 스크롤하고 브라우저가 뒤로 가기의 자리를 스스로 되돌린다. 데스크톱 셸
 * 안에서는 문서가 잠기고 중앙 본문이 스크롤하므로(`global.css`의 `data-layout="app"`) 브라우저가
 * 되돌릴 것이 없다. 그래서 셸을 세울 때만 `useScrollRestoration`을 건다 — 탭 화면이 -520에서
 * 같은 이유로 걸어 둔 것과 같은 장치이고, 모바일 동작은 건드리지 않는다.
 */
import { useRef } from 'react';
import { Outlet } from 'react-router';
import { useAuth } from '../../contexts/authContext';
import { useScrollRestoration } from '../../hooks/useScrollRestoration';
import { useShellSize } from '../../hooks/useShellSize';
import DesktopShell from '../DesktopShell';
import styles from './FullScreenLayout.module.css';

export default function FullScreenLayout() {
  const bodyRef = useRef<HTMLDivElement>(null);
  const { status } = useAuth();
  const desktop = useShellSize() !== 'mobile';
  const shell = desktop && status === 'authenticated';

  useScrollRestoration(bodyRef);

  if (shell) {
    return (
      <DesktopShell bodyRef={bodyRef}>
        <Outlet />
      </DesktopShell>
    );
  }

  return (
    <div className={styles.layout} data-layout="fullscreen">
      <Outlet />
    </div>
  );
}
