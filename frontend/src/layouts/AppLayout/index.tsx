/*
 * 하단 내비가 있는 화면의 껍데기.
 * 적용 화면은 2.1 · 2.2 · 2.2-A · 3.1 · 3.2와 범위 밖 3탭이다 (REF-01 적용 화면).
 *
 * 화면 헤더는 화면마다 구성이 달라 각 화면이 ScreenHeader를 직접 렌더한다.
 *
 * 본문이 스크롤하는 곳이 여기 한 곳이라, 뒤로 가기로 돌아왔을 때 보던 자리를 되돌리는 것도
 * 여기서 한다(업무 -520, `useScrollRestoration`).
 *
 * ─────────────────────────── 데스크톱 셸 (업무 -782)
 *
 * 폭에 따라 껍데기를 셋 중 하나로 세운다. 기준점과 그 근거는 `styles/tokens.css`의
 * `데스크톱 셸` 주석에 있다.
 *
 * - `mobile`(1024px 미만) — 아래 `모바일 셸`. **지금까지와 한 글자도 다르지 않다**
 * - `desktop`(1024px 이상) — 좌측 고정 내비 · 상단 통합 검색 바 · 중앙 한 단
 * - `wide`(1280px 이상) — 위에 우측 보조 패널 자리가 더 붙는다
 *
 * **스크롤 책임은 어느 단계에서도 한 겹이다.** 문서는 움직이지 않고(`global.css`의
 * `data-layout="app"` 잠금) 본문만 자기 안에서 스크롤한다. 데스크톱에서는 좌측 띠와 상단 바가
 * 그 바깥에 있어 함께 밀려 올라가지 않고, 우측 패널은 중앙과 따로 스크롤한다 — 카드 몇 장인
 * 패널을 긴 본문에 묶으면 패널 아래가 빈 채로 딸려 다닌다.
 *
 * 폭이 바뀌면 껍데기가 통째로 갈린다. 본문 요소가 다른 요소로 바뀌므로 스크롤 위치는
 * 그때 한 번 맨 위로 돌아간다. 창 크기를 끌어 바꾸는 동안의 일이라 그대로 둔다.
 */
import { useRef, useState } from 'react';
import { Outlet } from 'react-router';
import BottomNav from '../../components/BottomNav';
import DesktopNav from '../../components/DesktopNav';
import DesktopTopBar from '../../components/DesktopTopBar';
import { DesktopAsideContext } from '../../contexts/desktopAsideContext';
import { useScrollRestoration } from '../../hooks/useScrollRestoration';
import { useShellAccount } from '../../hooks/useShellAccount';
import { useShellSize } from '../../hooks/useShellSize';
import styles from './AppLayout.module.css';

export default function AppLayout() {
  const bodyRef = useRef<HTMLDivElement>(null);
  /*
   * 우측 패널 요소. 콜백 ref로 받는다 — 페이지가 여기에 포털을 걸려면 요소가 실제로 붙은
   * 뒤에 다시 그려야 하고, `useRef`는 그 순간을 알려주지 않는다. 좁아져서 패널이 사라지면
   * React가 `null`로 다시 불러 주므로 낡은 요소를 가리킨 채 남지 않는다.
   */
  const [asideSlot, setAsideSlot] = useState<HTMLElement | null>(null);
  const shell = useShellSize();
  const desktop = shell !== 'mobile';
  const account = useShellAccount(desktop);

  useScrollRestoration(bodyRef);

  if (!desktop) {
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

  return (
    <DesktopAsideContext.Provider value={asideSlot}>
      <div className={styles.desktop} data-layout="app">
        {/*
          좌측 내비가 생기면서 본문까지 Tab 11번(로고 · 항목 다섯 · 설정 · 계정 · 검색 · 알림 ·
          프로필)이 됐다. 키보드로 쓰는 사람은 화면을 옮길 때마다 그 열한 번을 다시 지난다.

          주소에 `#`을 남기지 않으려고 링크가 아니라 단추로 두고 본문에 직접 초점을 준다.
          해시 이동은 react-router에게 새 이동이라 4.1 검색이 그것을 바깥에서 온 이동으로 보고
          같은 조건으로 다시 검색한다.

          모바일에는 두지 않는다. 헤더의 아이콘 둘만 지나면 본문이라 건너뛸 거리가 없다.
        */}
        <button type="button" className={styles.skip} onClick={() => bodyRef.current?.focus()}>
          본문으로 건너뛰기
        </button>

        <DesktopNav account={account} />

        <div className={styles.column}>
          <DesktopTopBar account={account} />

          <div className={styles.split}>
            {/* 건너뛰기 단추가 초점을 주는 곳. `-1`이라 Tab 순서에는 들어가지 않는다 */}
            <main ref={bodyRef} className={styles.main} tabIndex={-1}>
              <Outlet />
            </main>

            {shell === 'wide' ? (
              <aside ref={setAsideSlot} className={styles.aside} aria-label="보조 정보" />
            ) : null}
          </div>
        </div>
      </div>
    </DesktopAsideContext.Provider>
  );
}
