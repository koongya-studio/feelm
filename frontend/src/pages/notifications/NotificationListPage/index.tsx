/*
 * 8.1 앱 내 알림 목록
 *
 * 이동표
 * - 2.1 → 8.1 홈의 알림 아이콘
 * - 8.1 → 2.4-A 감상 확인 · 5.6 파티 초대 · 5.8 파티 초대 수락 · 3.3 리포트 발급
 *
 * **파티 초대 수락 알림은 파티가 끝났거나 내가 나갔으면 이동하지 않고 까닭을 말한다**(업무 -866).
 * 알림은 그 뒤에도 목록에 남는데, 5.8로 보내면 일반 오류만 보였다(`destination.ts`).
 *
 * 알림 종류를 나누지 않고 최신순 단일 목록으로 보여준다(사용자 결정 2026-09-17).
 *
 * API-070~074를 서버 정본으로 사용한다. 읽지 않은 알림에는 빨간 점을 두고, 누르면 서버에
 * 읽음을 기록한 뒤 이동한다. 전체 읽음과 삭제도 새로고침 뒤 유지된다. 화면의 `삭제`는
 * API-074의 숨김 처리라 원본 파티 초대·감상 기록을 지우지 않는다.
 * `[?]` 와이어프레임 8.1에는 뒤로 가기가 없다. 전체화면이라 돌아갈 길이 없어 두었다.
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import { path } from '../../../routes/paths';
import ScreenHeader from '../../../components/ScreenHeader';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import {
  dismissNotification,
  getNotifications,
  readAllNotifications,
  readNotification,
} from '../../../api/notifications';
import { formatRelativeTime } from '../../../utils/date';
import { notificationPath, unavailableReason } from '../destination';
import NotificationIcon from './NotificationIcon';
import styles from './NotificationListPage.module.css';
import type { NotificationItem } from '../../../types/notification';

function lifecycleText(type: NotificationItem['type']): string {
  switch (type) {
    case 'PARTY_INVITE':
      return '초대 응답 시 삭제 · 최대 90일 보관';
    case 'PARTY_INVITE_ACCEPTED':
      return '90일 후 자동 삭제';
    case 'WATCH_CONFIRM':
      return '감상 확인 시 삭제 · OTT 이동 후 7일까지 보관';
    case 'REPORT_ISSUED':
      return '리포트는 MY에서 다시 볼 수 있어요 · 90일 후 자동 삭제';
  }
}

export default function NotificationListPage() {
  const navigate = useNavigate();

  const [items, setItems] = useState<NotificationItem[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [actionFailed, setActionFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  /** 알림이 가리키는 곳으로 갈 수 없는 까닭 */
  const [notice, setNotice] = useState<Awaited<ReturnType<typeof unavailableReason>>>(null);
  /** 누른 알림의 목적지를 확인하는 중이다. 거듭 누른 것은 무시한다 */
  const openingRef = useRef(false);

  useEffect(() => {
    let active = true;

    getNotifications()
      .then((value) => {
        if (active) {
          setItems(value);
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

  async function open(item: NotificationItem) {
    if (openingRef.current) return;

    openingRef.current = true;
    setNotice(null);

    try {
      // 갈 수 없어도 읽은 것이다. 목적지 확인과 함께 보낸다
      const [reason] = await Promise.all([
        unavailableReason(item),
        item.readAt === null
          ? readNotification(item.notificationId).then(
              () => markRead(item.notificationId),
              // 대상 화면 진입은 막지 않는다. 목록에 돌아오면 서버 상태를 다시 조회한다.
              () => undefined,
            )
          : undefined,
      ]);

      if (reason) {
        setNotice(reason);
        return;
      }

      void navigate(notificationPath(item));
    } finally {
      openingRef.current = false;
    }
  }

  function markRead(notificationId: number) {
    const readAt = new Date().toISOString();

    setItems(
      (current) =>
        current?.map((item) =>
          item.notificationId === notificationId && item.readAt === null
            ? { ...item, readAt }
            : item,
        ) ?? null,
    );
  }

  async function readAll() {
    if (!items || busy) return;

    setBusy(true);
    setActionFailed(false);

    try {
      await readAllNotifications();
      const readAt = new Date().toISOString();
      setItems(items.map((item) => (item.readAt === null ? { ...item, readAt } : item)));
    } catch {
      setActionFailed(true);
    } finally {
      setBusy(false);
    }
  }

  async function dismiss(item: NotificationItem) {
    if (busy) return;

    setBusy(true);
    setActionFailed(false);

    try {
      await dismissNotification(item.notificationId);
      setItems(
        (current) =>
          current?.filter(({ notificationId }) => notificationId !== item.notificationId) ?? [],
      );
    } catch {
      setActionFailed(true);
    } finally {
      setBusy(false);
    }
  }

  const hasUnread = items?.some((item) => item.readAt === null) ?? false;
  const header = (
    <ScreenHeader
      title="알림"
      showBack
      fallbackTo={path.my()}
      action={
        items && items.length > 0 ? (
          <button
            type="button"
            className={styles.readAll}
            disabled={busy || !hasUnread}
            onClick={() => void readAll()}
          >
            모두 읽음
          </button>
        ) : undefined
      }
    />
  );

  if (failed) {
    return (
      <>
        {header}
        <StatusView title="알림을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
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

  if (!items) {
    return header;
  }

  return (
    <>
      {header}

      <div className={styles.page}>
        {actionFailed ? (
          <p className={styles.actionError} role="alert">
            알림을 변경하지 못했어요. 다시 시도해 주세요.
          </p>
        ) : null}

        {items.length === 0 ? (
          <StatusView variant="empty" size="inline" title="새 알림이 없어요" />
        ) : (
          <ul className={styles.list} aria-label="알림 목록">
            {items.map((item) => (
              <li key={item.notificationId} className={styles.item}>
                <button
                  type="button"
                  className={styles.row}
                  data-read={item.readAt !== null}
                  onClick={() => void open(item)}
                >
                  <span className={styles.icon} aria-hidden="true">
                    <NotificationIcon type={item.type} />
                  </span>

                  <span className={styles.text}>
                    <span className={styles.title}>
                      {item.title}
                      {item.readAt === null ? (
                        <span className={styles.unread}> · 읽지 않음</span>
                      ) : null}
                    </span>
                    <span className={styles.body}>{item.body}</span>
                    <span className={styles.meta}>
                      <span className={styles.time}>{formatRelativeTime(item.notifiedAt)}</span>
                      <span aria-hidden="true">·</span>
                      <span className={styles.lifecycle}>{lifecycleText(item.type)}</span>
                    </span>
                  </span>
                </button>

                <button
                  type="button"
                  className={styles.dismiss}
                  disabled={busy}
                  aria-label={`${item.title} 알림 삭제`}
                  onClick={() => void dismiss(item)}
                >
                  삭제
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {notice ? (
        <div className={styles.snackbarSlot}>
          <Snackbar
            actionLabel={notice.partyPath ? '파티로 이동' : '확인'}
            onAction={() => {
              if (notice.partyPath) void navigate(notice.partyPath);
              setNotice(null);
            }}
          >
            {notice.message}
          </Snackbar>
        </div>
      ) : null}
    </>
  );
}
