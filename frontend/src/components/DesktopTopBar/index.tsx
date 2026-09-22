/*
 * 데스크톱 상단 바 — 통합 검색 · 알림 · 프로필 (업무 S15P21E106-782).
 *
 * 2026-09-21 사용자 시안 5종이 모두 같은 줄을 위에 그렸다. 왼쪽 끝까지 늘어난 검색 입력과
 * 오른쪽 끝의 알림 종 · 프로필 아바타다.
 *
 * **검색은 입력이고 좌측 내비의 `검색`은 화면이다.** 둘이 같은 4.1로 가지만 하는 일이 다르다 —
 * 여기서는 무엇을 찾을지 이미 아는 사람이 바로 적고, 내비 항목은 필터를 만지러 가는 길이다.
 * 적고 Enter를 치면 검색어를 주소에 싣고 4.1로 간다(`path.searchWith`).
 *
 * 알림 종은 `AppHeader`와 같은 일을 한다 — 읽지 않은 수를 스스로 받고, 못 받으면 점 없이 둔다.
 * 두 헤더가 같은 요청을 두 번 내지 않는 것은 `AppHeader`가 데스크톱에서 그려지지 않기 때문이다.
 *
 * 모바일 헤더(`AppHeader`)를 고쳐 쓰지 않고 따로 둔다. 모바일은 워드마크 + 아이콘 둘이고
 * 데스크톱은 로고가 좌측 띠로 올라가 그 자리에 입력이 들어온다 — 같은 줄의 다른 구성이다.
 */
import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { getUnreadNotificationCount } from '../../api/notifications';
import { path } from '../../routes/paths';
import Avatar from '../Avatar';
import { BellIcon, SearchIcon } from '../Icon';
import type { ShellAccount } from '../../hooks/useShellAccount';
import styles from './DesktopTopBar.module.css';

type Props = {
  account: ShellAccount | null;
};

export default function DesktopTopBar({ account }: Props) {
  const navigate = useNavigate();
  const [keyword, setKeyword] = useState('');
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

  /*
   * 빈 검색어로도 4.1에 간다. 조건 없는 4.1은 최근 검색어와 필터를 보여주는 화면이라
   * 빈 채로 들어가는 것이 막다른 길이 아니다.
   *
   * **보내고 나면 입력을 비운다**(사용자 결정 2026-09-21, 시안의 상단 바도 비어 있다).
   * 이 바는 셸에 있어 화면이 바뀌어도 살아 있으므로, 비우지 않으면 4.1에 도착한 뒤 상단 바와
   * 화면 입력에 같은 검색어가 위아래로 두 번 보인다. 보낸 검색어의 주인은 그때부터 4.1이고,
   * 이 바는 다음 검색을 받는 빈 자리로 돌아간다.
   */
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    navigate(path.searchWith(keyword));
    setKeyword('');
  }

  return (
    <header className={styles.bar}>
      <form className={styles.search} role="search" onSubmit={onSubmit}>
        <span className={styles.searchIcon}>
          <SearchIcon />
        </span>
        <input
          type="search"
          className={styles.input}
          value={keyword}
          placeholder="영화, 감독, 배우로 검색해보세요."
          aria-label="영화, 감독, 배우 검색"
          onChange={(event) => setKeyword(event.target.value)}
        />
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

        {account ? (
          <Link to={path.my()} className={styles.profile} aria-label={`${account.nickname} 프로필`}>
            <Avatar size={40} nickname={account.nickname} src={account.avatarUrl} />
          </Link>
        ) : null}
      </div>
    </header>
  );
}
