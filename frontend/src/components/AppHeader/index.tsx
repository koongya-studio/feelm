/*
 * 탭 화면 공통 헤더 — 워드마크 · 검색 · 알림 (업무 -676, UI/UX 분석 리포트 P1-01).
 *
 * 검색 · 알림으로 가는 길이 3.1 필름 헤더에만 있어, 파티 · MY에서 영화를 찾으려면 필름 탭을
 * 먼저 거쳐야 했다. 하단 탭은 세 개로 두고(사용자 결정 2026-09-19) **필름 · 파티 · MY 세 탭
 * 화면 맨 위에 같은 헤더를 둔다.** 아이콘은 탭마다 같은 자리 · 같은 순서(검색 → 알림,
 * 사용자 지시 2026-09-16 업무 -461)다.
 *
 * 워드마크는 제목(h1)이 아니다. 화면의 h1은 그 화면의 이름(`나의 취향 필름` · `파티팝콘` ·
 * `프로필`)이 맡는다 — 세 탭이 모두 `Feelm`을 h1로 두면 화면을 구분하지 못한다.
 *
 * 읽지 않은 알림 수는 헤더가 스스로 받는다. 배지는 보조 정보라 실패하면 점 없이 둔다.
 *
 * **1024px 이상에서는 그리지 않는다**(업무 -782). 데스크톱 셸이 워드마크를 좌측 띠로, 검색과
 * 알림을 상단 바로 올려 같은 셋을 이미 내놓는다. 둘 다 그리면 한 화면에 검색 진입점이 둘이고
 * 읽지 않은 알림 수를 두 번 받으러 간다. 화면들이 이 헤더를 직접 그리므로(`FilmPage` 등)
 * 감추는 판단도 여기서 한다 — 그래야 화면 다섯이 각자 조건을 달지 않는다.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { retryNotificationQuery } from '../../api/notificationRetry';
import { useNotificationRevision } from '../../hooks/useNotificationRevision';
import { getUnreadNotificationCount } from '../../api/notifications';
import { useShellSize } from '../../hooks/useShellSize';
import { path } from '../../routes/paths';
import { BellIcon, SearchIcon } from '../Icon';
import Wordmark from '../Wordmark';
import styles from './AppHeader.module.css';

export default function AppHeader() {
  const [unread, setUnread] = useState(0);
  const revision = useNotificationRevision();
  const desktop = useShellSize() !== 'mobile';

  useEffect(() => {
    if (desktop) return;

    let active = true;
    const controller = new AbortController();

    retryNotificationQuery(getUnreadNotificationCount, controller.signal)
      .then((count) => {
        if (active) setUnread(count);
      })
      .catch(() => undefined);

    return () => {
      active = false;
      controller.abort();
    };
  }, [desktop, revision]);

  if (desktop) return null;

  return (
    <header className={styles.header}>
      <Wordmark />

      <div className={styles.actions}>
        <Link to={path.search()} className={styles.action} aria-label="검색">
          <SearchIcon />
        </Link>

        <Link
          to={path.notifications()}
          className={styles.action}
          aria-label={unread > 0 ? `알림, 읽지 않은 알림 ${unread}개` : '알림'}
        >
          <BellIcon />
          {unread > 0 ? <span className={styles.dot} data-testid="notification-dot" /> : null}
        </Link>
      </div>
    </header>
  );
}
