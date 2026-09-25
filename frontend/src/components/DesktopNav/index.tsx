/*
 * 데스크톱 좌측 고정 내비 (업무 S15P21E106-782).
 *
 * 2026-09-21 사용자 시안 5종이 모두 같은 띠를 왼쪽에 그렸다 — 로고 · 다섯 항목 · 설정 · 계정 행이다.
 *
 * **다섯 항목이다**(`routes/paths.ts`의 `DESKTOP_NAV_MATCH`). 하단 내비가 셋인 것은 폭 412px에
 * 터치 타깃 44px을 늘어놓을 자리가 없어서였고(업무 -461), 검색을 헤더로 올린 것도(업무 -676)
 * 같은 제약에서 나왔다. 세로로 긴 띠에는 그 제약이 없어 검색과 추천 영화가 제 자리를 갖는다.
 * **하단 내비는 셋 그대로다** — 이 컴포넌트는 1024px 이상에서만 그려진다.
 *
 * 어디에 있는지는 왼쪽 빨강 바와 연한 빨강 면으로 말한다. 빨강을 면으로 쓰지 않는다는 규칙
 * (`tokens.css`의 `--color-brand`)의 예외가 아니다 — `--color-brand-tint`는 선택 상태용으로
 * 이미 있는 아주 옅은 값이고, 바는 면적을 차지하지 않는 표식이다.
 *
 * 계정 행의 이메일과 사진은 셸이 받아 넘긴다(`hooks/useShellAccount`). 없으면 이름만 둔다.
 */
import type { ReactNode } from 'react';
import { Link, matchPath, useLocation } from 'react-router';
import DesktopAccountMenu from '../DesktopAccountMenu';
import { CompassIcon, FilmIcon, MyIcon, PartyIcon, SearchIcon, SettingsIcon } from '../Icon';
import Wordmark from '../Wordmark';
import { DESKTOP_NAV_MATCH, path } from '../../routes/paths';
import type { ShellAccount } from '../../hooks/useShellAccount';
import styles from './DesktopNav.module.css';

type Item = {
  key: keyof typeof DESKTOP_NAV_MATCH;
  label: string;
  to: string;
  icon: ReactNode;
};

/** 위에서 아래 순서가 시안 그대로다. 맨 위가 앱의 시작 화면인 취향 필름이다 */
const ITEMS: Item[] = [
  { key: 'film', label: '취향 필름', to: path.film(), icon: <FilmIcon /> },
  { key: 'recommendations', label: '추천 영화', to: path.recommendations(), icon: <CompassIcon /> },
  { key: 'party', label: '파티팝콘', to: path.party(), icon: <PartyIcon /> },
  { key: 'search', label: '영화 탐색', to: path.search(), icon: <SearchIcon /> },
  { key: 'my', label: 'MY', to: path.my(), icon: <MyIcon /> },
];

/**
 * 현재 경로가 해당 항목에 속하는지 판정한다. 하위 경로도 상위 항목의 선택 상태를 유지한다.
 *
 * `BottomNav`의 같은 이름 함수와 규칙은 같고 보는 표만 다르다. 표를 합치면 하단 3탭 계약과
 * 좌측 5항목이 한 값에 묶여 한쪽을 고칠 때 다른 쪽이 조용히 바뀐다.
 */
function isItemActive(key: keyof typeof DESKTOP_NAV_MATCH, pathname: string) {
  if (key === 'my' && isSettingsActive(pathname)) return false;
  return DESKTOP_NAV_MATCH[key].some((pattern) =>
    matchPath({ path: pattern, end: false }, pathname),
  );
}

function isSettingsActive(pathname: string) {
  return Boolean(matchPath({ path: path.settings(), end: false }, pathname));
}

type Props = {
  account: ShellAccount | null;
};

export default function DesktopNav({ account }: Props) {
  const { pathname } = useLocation();
  const settingsActive = isSettingsActive(pathname);

  return (
    <div className={styles.rail}>
      <Link to={path.film()} className={styles.brand}>
        <Wordmark size={40} />
      </Link>

      <nav className={styles.nav} aria-label="주요 화면">
        {ITEMS.map((item) => {
          const active = isItemActive(item.key, pathname);

          return (
            <Link
              key={item.key}
              to={item.to}
              className={styles.item}
              data-active={active}
              aria-current={active ? 'page' : undefined}
            >
              <span className={styles.icon}>{item.icon}</span>
              <span className={styles.label}>{item.label}</span>
            </Link>
          );
        })}
      </nav>

      <div className={styles.foot}>
        <Link
          to={path.settings()}
          className={styles.item}
          data-active={settingsActive}
          aria-current={settingsActive ? 'page' : undefined}
        >
          <span className={styles.icon}>
            <SettingsIcon />
          </span>
          <span className={styles.label}>설정</span>
        </Link>

        {account ? <DesktopAccountMenu account={account} /> : null}
      </div>
    </div>
  );
}
