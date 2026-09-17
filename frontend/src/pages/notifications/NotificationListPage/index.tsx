/*
 * 8.1 앱 내 알림 목록
 *
 * 이동표
 * - 2.1 → 8.1 홈의 알림 아이콘
 * - 8.1 → 2.4-A 감상 확인 · 5.6 파티 초대 · 2.2 오늘의 추천 · 3.1 취향 필름 추가
 *
 * `5.6 ⇢ 8.1`(초대 거절 후 복귀)은 **없앴다**(업무 -595, CR-27). 이 목록이 전체 목업이라
 * 거절한 초대가 그대로 남아 있는 화면으로 돌아갔다. 5.6은 답한 뒤 5.1로 간다.
 *
 * 요소는 와이어프레임 그대로다 — `알림`, `전체 · 추천 · 이벤트` 탭, 알림 네 건(아이콘 · 제목 ·
 * 한 줄 · 시각).
 *
 * **알림 목록 계약이 없다.** 와이어프레임대로 그리기로 했다(팀 결정 2026-09-13).
 * `api/notifications.ts` 머리말.
 *
 * - 읽지 않은 알림에는 빨간 점을 둔다. 빨강을 쓰는 몇 안 되는 자리다(`design-concept.md`
 *   2.2 `읽지 않음 표식`). 누르면 읽음으로 남기고 간다
 * - 탭은 화면 안에서 목록만 거른다. 이동표에 탭별 화살표가 없다
 *
 * `[?]` `추천` · `이벤트`가 무엇을 담는지 적혀 있지 않다. 오늘의 추천을 `추천`에, 나머지(감상 확인 ·
 * 파티 · 필름)를 `이벤트`에 두었다.
 * `[?]` 와이어프레임 8.1에는 뒤로 가기가 없다. 전체화면이라 돌아갈 길이 없어 두었다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getNotifications, readNotification } from '../../../api/notifications';
import { path } from '../../../routes/paths';
import { formatRelativeTime } from '../../../utils/date';
import NotificationIcon from './NotificationIcon';
import styles from './NotificationListPage.module.css';
import type { NotificationView } from '../../../types/unspecified';

const TABS = [
  { key: 'all', label: '전체' },
  { key: 'recommendation', label: '추천' },
  { key: 'event', label: '이벤트' },
] as const;

type TabKey = (typeof TABS)[number]['key'];

function inTab(item: NotificationView, tab: TabKey) {
  if (tab === 'all') return true;

  return (item.type === 'RECOMMENDATION') === (tab === 'recommendation');
}

/** 알림이 가리키는 화면. 이동표 8.1의 네 화살표다 */
function notificationTarget(item: NotificationView): string {
  switch (item.type) {
    case 'WATCH_CONFIRM':
      return path.watchConfirm(item.targetId ?? 0);
    case 'PARTY_INVITE':
      return path.partyInvitation(item.targetId ?? 0);
    case 'RECOMMENDATION':
      return path.recommendations();
    case 'FRAME_ADDED':
      return path.film();
  }
}

export default function NotificationListPage() {
  const navigate = useNavigate();

  const [items, setItems] = useState<NotificationView[] | null>(null);
  const [tab, setTab] = useState<TabKey>('all');
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

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

  async function open(item: NotificationView) {
    await readNotification(item.notificationId);
    void navigate(notificationTarget(item));
  }

  const header = <ScreenHeader title="알림" showBack />;

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

  const visible = items.filter((item) => inTab(item, tab));

  return (
    <>
      {header}

      <div className={styles.page}>
        <div className={styles.tabs} role="tablist" aria-label="알림 종류">
          {TABS.map((item) => (
            <button
              key={item.key}
              type="button"
              role="tab"
              className={styles.tab}
              aria-selected={tab === item.key}
              onClick={() => setTab(item.key)}
            >
              {item.label}
            </button>
          ))}
        </div>

        {visible.length === 0 ? (
          <StatusView variant="empty" size="inline" title="새 알림이 없어요" />
        ) : (
          <ul className={styles.list} role="tabpanel">
            {visible.map((item) => (
              <li key={item.notificationId}>
                <button
                  type="button"
                  className={styles.row}
                  data-read={item.read}
                  onClick={() => void open(item)}
                >
                  <span className={styles.icon} aria-hidden="true">
                    <NotificationIcon type={item.type} />
                  </span>

                  <span className={styles.text}>
                    <span className={styles.title}>
                      {item.title}
                      {item.read ? null : <span className={styles.unread}> · 읽지 않음</span>}
                    </span>
                    <span className={styles.body}>{item.body}</span>
                    <span className={styles.time}>{formatRelativeTime(item.createdAt)}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
