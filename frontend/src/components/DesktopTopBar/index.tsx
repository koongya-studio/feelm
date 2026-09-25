/*
 * 데스크톱 상단 바 — 통합 검색 · 알림 (업무 S15P21E106-782).
 *
 * 2026-09-21 사용자 시안 5종이 모두 같은 줄을 위에 그렸다. 왼쪽 끝까지 늘어난 검색 입력과
 * 오른쪽 끝의 알림 종이다. 계정 메뉴는 좌측 내비에서 연다(업무 -909).
 *
 * 다른 화면에서는 검색어를 주소에 싣고 탐색 화면으로 들어간다. 탐색 화면에서는 이 입력이
 * 유일한 검색창이며, 타이핑·제출·지우기를 화면의 검색 상태에 바로 전달한다.
 *
 * 알림 종은 `AppHeader`와 같은 일을 한다 — 읽지 않은 수를 스스로 받고, 못 받으면 점 없이 둔다.
 * 두 헤더가 같은 요청을 두 번 내지 않는 것은 `AppHeader`가 데스크톱에서 그려지지 않기 때문이다.
 *
 * 모바일 헤더(`AppHeader`)를 고쳐 쓰지 않고 따로 둔다. 모바일은 워드마크 + 아이콘 둘이고
 * 데스크톱은 로고가 좌측 띠로 올라가 그 자리에 입력이 들어온다 — 같은 줄의 다른 구성이다.
 */
import { useContext, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { getUnreadNotificationCount } from '../../api/notifications';
import { DesktopSearchContext } from '../../contexts/desktopSearchContext';
import { path } from '../../routes/paths';
import { BellIcon, ClearIcon, SearchIcon } from '../Icon';
import styles from './DesktopTopBar.module.css';

export default function DesktopTopBar() {
  const navigate = useNavigate();
  const location = useLocation();
  const desktopSearch = useContext(DesktopSearchContext);
  const [entryKeyword, setEntryKeyword] = useState('');
  const onSearchPage = location.pathname === path.search() && desktopSearch !== null;
  const keyword = onSearchPage ? (desktopSearch?.keyword ?? '') : entryKeyword;
  const [unread, setUnread] = useState(0);

  useEffect(() => {
    let active = true;

    getUnreadNotificationCount()
      .then((count) => {
        if (active) setUnread(count);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  /** 다른 화면에서는 Enter로 탐색 화면에 들어간다. 탐색 화면에서는 현재 필터를 보존해 제출한다. */
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (onSearchPage && desktopSearch?.handlers.current) {
      desktopSearch.handlers.current.submit(keyword);
      return;
    }
    navigate(path.searchWith(keyword));
    setEntryKeyword('');
  }

  return (
    <header className={styles.bar}>
      <form className={styles.search} role="search" onSubmit={onSubmit}>
        <span className={styles.searchIcon}>
          <SearchIcon />
        </span>
        <input
          ref={desktopSearch?.inputRef}
          type="search"
          className={styles.input}
          value={keyword}
          placeholder="영화, 감독, 배우로 검색해보세요."
          aria-label="영화, 감독, 배우 검색"
          onCompositionStart={() => {
            if (onSearchPage) desktopSearch?.handlers.current?.compositionStart();
          }}
          onCompositionEnd={(event) => {
            if (onSearchPage)
              desktopSearch?.handlers.current?.compositionEnd(event.currentTarget.value);
          }}
          onChange={(event) => {
            if (onSearchPage) {
              desktopSearch?.setKeyword(event.target.value);
              desktopSearch?.handlers.current?.change(event.target.value);
            } else {
              setEntryKeyword(event.target.value);
            }
          }}
        />
        {onSearchPage && keyword ? (
          <button
            type="button"
            className={styles.clear}
            aria-label="검색어 지우기"
            onClick={() => desktopSearch?.handlers.current?.clear()}
          >
            <ClearIcon />
          </button>
        ) : null}
      </form>

      <div className={styles.actions}>
        <Link
          to={path.notifications()}
          className={styles.action}
          aria-label={unread > 0 ? `알림, 읽지 않은 알림 ${unread}개` : '알림'}
        >
          <BellIcon />
          {unread > 0 ? (
            <span className={styles.dot} data-testid="desktop-notification-dot" />
          ) : null}
        </Link>
      </div>
    </header>
  );
}
