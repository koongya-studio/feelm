/*
 * 하단 내비게이션.
 *
 * 화면 폭을 채우는 바가 아니라 **떠 있는 알약**이다. 내용에 맞춰 좁게 잡고 좌우
 * 여백을 남겨, 콘텐츠가 알약 뒤로 지나가며 아래까지 이어져 보이게 한다
 * (`docs/screens/design-concept.md`).
 *
 * 4탭이다. 와이어프레임 REF-01은 검색을 포함한 5탭이었으나, 검색은 상단 헤더
 * 아이콘으로 옮겨 탭 하나를 줄였다. 남은 넷의 폭이 넓어지고 검색 진입점이
 * 중복되지 않는다. `/search` 라우트는 그대로 살아 있고 헤더에서 들어간다.
 *
 * 필름 탭만 테두리로 강조하던 REF-01 사양도 뺐다. 활성 탭은 색으로만 구분한다.
 * 알약 안에 강조 면을 하나 더 두면 화면에서 solid 요소가 둘이 되어, 주 버튼과
 * 경쟁한다.
 */
import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { FilmIcon, HomeIcon, MyIcon, PartyIcon } from '../Icon';
import { path, TAB_MATCH } from '../../routes/paths';
import styles from './BottomNav.module.css';

type Tab = {
  key: keyof typeof TAB_MATCH;
  label: string;
  to: string;
  icon: ReactNode;
};

const TABS: Tab[] = [
  { key: 'home', label: '홈', to: path.home(), icon: <HomeIcon /> },
  { key: 'film', label: '필름', to: path.film(), icon: <FilmIcon /> },
  { key: 'party', label: '파티', to: path.party(), icon: <PartyIcon /> },
  { key: 'my', label: 'MY', to: path.my(), icon: <MyIcon /> },
];

/** 현재 경로가 해당 탭에 속하는지 판정한다. 하위 경로도 상위 탭의 선택 상태를 유지한다. */
function isTabActive(tabKey: keyof typeof TAB_MATCH, pathname: string) {
  return TAB_MATCH[tabKey].some(
    (pattern) => pathname === pattern || (pattern !== '/' && pathname.startsWith(`${pattern}/`)),
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
