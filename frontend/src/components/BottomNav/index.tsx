/*
 * 하단 내비게이션 바.
 * 사양은 와이어프레임 REF-01 하단 내비게이션 가이드를 그대로 따른다.
 *
 * - 5개 탭: 홈 · 검색 · 필름 · 파티 · MY
 * - 가운데 필름 탭은 테두리를 둘러 약간만 강조한다. 플로팅 버튼이 아니다
 * - 선택 #3C3A36, 비선택 #A5A29B, 높이 66px, 하단 Safe Area 여백 자동 확보
 *
 * 검색 · 파티 · MY는 이번 단계 구현 범위가 아니지만 REF-01의 5탭 구성을
 * 검증하기 위해 탭을 모두 표시하고 자리 화면으로 이동한다.
 */
import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { FilmIcon, HomeIcon, MyIcon, PartyIcon, SearchIcon } from '../Icon';
import { path, TAB_MATCH } from '../../routes/paths';
import styles from './BottomNav.module.css';

type Tab = {
  key: keyof typeof TAB_MATCH;
  label: string;
  to: string;
  icon: ReactNode;
  /** 필름 탭만 테두리로 강조한다 */
  emphasized?: boolean;
};

const TABS: Tab[] = [
  { key: 'home', label: '홈', to: path.home(), icon: <HomeIcon /> },
  { key: 'search', label: '검색', to: path.search(), icon: <SearchIcon /> },
  { key: 'film', label: '필름', to: path.film(), icon: <FilmIcon />, emphasized: true },
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
            {tab.emphasized ? <span className={styles.emphasis}>{tab.icon}</span> : tab.icon}
            <span className={styles.label} data-emphasized={tab.emphasized}>
              {tab.label}
            </span>
          </Link>
        );
      })}
    </nav>
  );
}
