/*
 * 7.8-D 차단 사용자 관리
 *
 * 이동표: 7.8 → 7.8-D · 7.2-B ⇢ 7.8-D 사용자 차단 후 차단 사용자 관리
 *
 * 요소는 와이어프레임 그대로다 — 헤더 아래 `N명 차단 중`, 차단의 효과 안내, 사람마다
 * 아바타 · 닉네임 · `차단 해제`. 연동 API는 API-022 목록 · API-021 해제다(업무 -394).
 *
 * - `차단 해제`는 확인 없이 바로 푼다. 와이어프레임에 확인 창이 없고, 되돌리려면 다시
 *   차단하면 된다
 * - 해제는 멱등이라(API-021) 실패는 네트워크나 세션 문제뿐이다. 그때는 목록에서 지우지
 *   않고 안내만 한다 — 지워 놓으면 서버에는 남은 차단이 화면에서 사라진다
 * - 닉네임은 누를 수 없다. 차단한 사람의 프로필은 보이지 않는다(API-020). 탈퇴한 사람도
 *   목록에 남아 프로필이 404다(API-022 처리 규칙)
 *
 * `[?]` 와이어프레임에 더 보기가 없어 첫 페이지(50명)만 그린다. 더 남아 있으면
 *       `N명 차단 중`을 감춘다 — 7.5 `총 N편`과 같은 처리다.
 */
import { useEffect, useState } from 'react';
import Avatar from '../../../components/Avatar';
import { path } from '../../../routes/paths';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getBlockedUserList, unblockUser } from '../../../api/settings';
import styles from './BlockedUsersPage.module.css';
import type { BlockedUser, BlockedUserList } from '../../../types/user';

export default function BlockedUsersPage() {
  const [list, setList] = useState<BlockedUserList | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  /** 해제하는 중인 사람. 같은 버튼을 두 번 누르지 않게 막는다 */
  const [pendingId, setPendingId] = useState<number | null>(null);
  const [notice, setNotice] = useState('');

  useEffect(() => {
    let active = true;

    getBlockedUserList()
      .then((value) => {
        if (active) {
          setList(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) setFailed(true);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  async function handleUnblock(user: BlockedUser) {
    setPendingId(user.userId);

    try {
      await unblockUser(user.userId);
      setList((current) =>
        current
          ? { ...current, items: current.items.filter((item) => item.userId !== user.userId) }
          : null,
      );
      setNotice(`${user.nickname} 님의 차단을 해제했어요`);
    } catch {
      setNotice('차단을 해제하지 못했어요. 잠시 후 다시 시도해 주세요');
    } finally {
      setPendingId(null);
    }
  }

  if (failed) {
    return (
      <>
        <ScreenHeader title="차단 사용자 관리" showBack fallbackTo={path.settings()} />

        <StatusView title="차단 목록을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  if (!list) {
    return <ScreenHeader title="차단 사용자 관리" showBack fallbackTo={path.settings()} />;
  }

  const users = list.items;

  return (
    <>
      <ScreenHeader
        title="차단 사용자 관리"
        subtitle={list.hasNext ? undefined : `${users.length}명 차단 중`}
        showBack
        fallbackTo={path.settings()}
      />

      <div className={styles.page}>
        <p className={styles.info}>
          차단한 사용자와는 서로의 프로필·취향 필름·팝콘·한줄평이 노출되지 않아요
        </p>

        {users.length === 0 ? (
          <StatusView variant="empty" size="inline" title="차단한 사용자가 없어요" />
        ) : (
          <ul className={styles.list}>
            {users.map((user) => (
              <li key={user.userId} className={styles.row}>
                <Avatar size={38} nickname={user.nickname} />
                <span className={styles.nickname}>{user.nickname}</span>
                <button
                  type="button"
                  className={styles.unblock}
                  aria-label={`${user.nickname} 차단 해제`}
                  disabled={pendingId === user.userId}
                  onClick={() => void handleUnblock(user)}
                >
                  차단 해제
                </button>
              </li>
            ))}
          </ul>
        )}

        {notice ? (
          <p className={styles.notice} role="status">
            {notice}
          </p>
        ) : null}
      </div>
    </>
  );
}
