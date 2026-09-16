/*
 * 하단 내비게이션.
 *
 * 화면 폭을 채우는 바가 아니라 **떠 있는 알약**이다. 좌우에 여백을 남겨 콘텐츠가
 * 알약 뒤로 지나가며 아래까지 이어져 보이게 한다(`docs/screens/design-concept.md`).
 *
 * **3탭이다** — `파티 · 필름 · MY`(사용자 결정 2026-09-16, 업무 -461).
 * 와이어프레임 REF-01은 검색을 포함한 5탭이었고, 그 뒤 검색을 헤더로 올려 4탭이 됐다가,
 * 홈을 없애면서 셋이 됐다. **가운데 필름이 앱의 시작 화면이다.**
 *
 * 가운데 탭을 돌출시키거나 크게 만들지 않는다. 셋이 같은 폭 · 같은 모양이고 어디에
 * 있는지는 색으로만 말한다. 알약 안에 강조 면을 하나 더 두면 화면에서 solid 요소가
 * 둘이 되어 주 버튼과 경쟁한다(REF-01의 필름 탭 테두리 사양도 같은 이유로 뺐다).
 */
import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { FilmIcon, MyIcon, PartyIcon } from '../Icon';
import { path, TAB_MATCH } from '../../routes/paths';
import styles from './BottomNav.module.css';

type Tab = {
  key: keyof typeof TAB_MATCH;
  label: string;
  to: string;
  icon: ReactNode;
};

/** 왼쪽부터 순서대로다. 가운데가 시작 화면인 필름이다 */
const TABS: Tab[] = [
  { key: 'party', label: '파티', to: path.party(), icon: <PartyIcon /> },
  { key: 'film', label: '필름', to: path.film(), icon: <FilmIcon /> },
  { key: 'my', label: 'MY', to: path.my(), icon: <MyIcon /> },
];

/**
 * 현재 경로가 해당 탭에 속하는지 판정한다. 하위 경로도 상위 탭의 선택 상태를 유지한다.
 *
 * 앱 진입점 `/`를 따로 거르지 않는다 — 어느 탭도 `/`를 들고 있지 않고, `/`는 화면을
 * 그리지 않고 `/film`으로 보내기만 한다(`routes/HomeEntry.tsx`).
 */
function isTabActive(tabKey: keyof typeof TAB_MATCH, pathname: string) {
  return TAB_MATCH[tabKey].some(
    (pattern) => pathname === pattern || pathname.startsWith(`${pattern}/`),
  );
}

export default function BottomNav() {
  const { pathname } = useLocation();

  return (
    <nav className={styles.nav} aria-label="주요 화면">
      {TABS.map((tab) => {
        const active = isTabActive(tab.key, pathname);

        return (
          <Link
            key={tab.key}
            to={tab.to}
            className={styles.tab}
            data-active={active}
            aria-current={active ? 'page' : undefined}
          >
            {tab.icon}
            <span className={styles.label}>{tab.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
