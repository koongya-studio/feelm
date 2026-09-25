/*
 * 1024px 이상에서 화면을 감싸는 공용 껍데기 (업무 S15P21E106-787).
 *
 * 좌측 고정 내비 · 상단 통합 검색 바 · 중앙 한 단, 그리고 1280px 이상에서 우측 보조 패널이다.
 * 업무 -782가 `AppLayout` 안에 직접 세웠던 뼈대를 그대로 옮겨 온 것이고, 모양과 동작은
 * 한 군데도 바꾸지 않았다.
 *
 * ─────────────────────────── 왜 떼어냈나
 *
 * -782는 탭 화면(`AppLayout`)에만 이 뼈대를 세웠다. 그래서 전체화면 화면(`FullScreenLayout`)은
 * 1024px 이상에서도 412px 폭으로 가운데 놓여 양옆이 비었고, 3.1 필름에서 2.4 영화 상세로
 * 들어가는 순간 좌측 내비가 사라졌다가 뒤로 가면 다시 나타났다.
 *
 * **두 레이아웃이 같은 껍데기를 쓰기로 했다**(사용자 결정 2026-09-22, 업무 -787). 그러려면
 * 뼈대가 한쪽 레이아웃 안에 있으면 안 된다 — 복사하면 둘이 따로 자란다.
 *
 * ─────────────────────────── 스크롤은 여기 한 겹이다
 *
 * 문서는 움직이지 않고(`global.css`의 `data-layout="app"` 잠금) `.main`만 자기 안에서
 * 스크롤한다. 좌측 띠와 상단 바는 그 바깥이라 함께 밀려 올라가지 않고, 우측 패널은 중앙과
 * 따로 스크롤한다 — 카드 몇 장인 패널을 긴 본문에 묶으면 패널 아래가 빈 채로 딸려 다닌다.
 *
 * 스크롤하는 요소를 쓰는 쪽에 알려야 해서 `bodyRef`를 받는다. 뒤로 가기로 돌아왔을 때 자리를
 * 되돌리는 것(`useScrollRestoration`)이 그 요소를 필요로 한다.
 */
import { useState } from 'react';
import DesktopNav from '../../components/DesktopNav';
import DesktopTopBar from '../../components/DesktopTopBar';
import { DesktopAsideContext } from '../../contexts/desktopAsideContext';
import { useShellAccount } from '../../hooks/useShellAccount';
import { useShellSize } from '../../hooks/useShellSize';
import styles from './DesktopShell.module.css';
import type { ReactNode, RefObject } from 'react';

type Props = {
  /** 세로로 스크롤하는 중앙 본문. 쓰는 쪽이 스크롤 위치를 되돌리는 데 쓴다 */
  bodyRef: RefObject<HTMLDivElement | null>;
  children: ReactNode;
};

export default function DesktopShell({ bodyRef, children }: Props) {
  /*
   * 우측 패널 요소. 콜백 ref로 받는다 — 페이지가 여기에 포털을 걸려면 요소가 실제로 붙은
   * 뒤에 다시 그려야 하고, `useRef`는 그 순간을 알려주지 않는다. 좁아져서 패널이 사라지면
   * React가 `null`로 다시 불러 주므로 낡은 요소를 가리킨 채 남지 않는다.
   */
  const [asideSlot, setAsideSlot] = useState<HTMLElement | null>(null);
  const shell = useShellSize();
  const account = useShellAccount(true);

  return (
    <DesktopAsideContext.Provider value={asideSlot}>
      <div className={styles.desktop} data-layout="app">
        {/*
          좌측 내비가 생기면서 본문까지 여러 Tab(로고 · 항목 다섯 · 설정 · 계정 · 검색 ·
          알림)을 지난다. 키보드로 쓰는 사람은 화면을 옮길 때마다 이를 다시 지난다.

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
          <DesktopTopBar />

          <div className={styles.split}>
            {/* 건너뛰기 단추가 초점을 주는 곳. `-1`이라 Tab 순서에는 들어가지 않는다 */}
            <main ref={bodyRef} className={styles.main} tabIndex={-1}>
              {children}
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
