/*
 * 8.2 잠금화면 푸시 알림 — OS 알림 예시
 *
 * 이동표: 8.2 → 2.4-A 잠금화면 감상 확인 알림 선택
 *
 * **웹앱이 그리는 화면이 아니다.** 와이어프레임 제목도 `OS 잠금화면 알림 예시`다. 푸시 발송은
 * 1차 MVP 범위가 아니므로(ADR-0011) 실제 알림이 오지 않는다. 그 장면을 확인하려고 예시로
 * 그리기로 했다(팀 결정 2026-09-13, 업무 -308). 들어오는 화살표가 없어 주소로만 연다.
 *
 * 요소 — 날짜와 시각, 알림 한 장(`feelm · 지금` · 제목 · 한 줄). 누르면 2.4-A로 간다.
 *
 * 잠금화면은 기기의 화면이라 어두운 판 하나로 그린다. 앱의 벽이 아니라 **걸린 그림**이다.
 * 날짜 · 시각은 와이어프레임 문구가 아니라 지금 시각이다 — 예시가 오래된 날짜를 보여줄
 * 이유가 없다.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';
import { path } from '../../../routes/paths';
import StatusView from '../../../components/StatusView';
import { getLatestPushNotification, readNotification } from '../../../api/notifications';
import { notificationPath } from '../destination';
import styles from './PushPreviewPage.module.css';
import type { NotificationView } from '../../../types/unspecified';

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

export default function PushPreviewPage() {
  const navigate = useNavigate();
  const [notification, setNotification] = useState<NotificationView | null | undefined>(undefined);

  useEffect(() => {
    let active = true;

    getLatestPushNotification()
      .then((value) => {
        if (active) setNotification(value);
      })
      .catch(() => {
        if (active) setNotification(null);
      });

    return () => {
      active = false;
    };
  }, []);

  const now = new Date();
  const date = `${now.getMonth() + 1}월 ${now.getDate()}일 ${WEEKDAYS[now.getDay()]}요일`;
  const time = `${now.getHours()}:${String(now.getMinutes()).padStart(2, '0')}`;

  async function open(item: NotificationView) {
    await readNotification(item.notificationId);
    void navigate(notificationPath(item.destination));
  }

  return (
    <>
      <ScreenHeader
        title="잠금화면 알림 예시"
        subtitle="실제 푸시 알림은 아직 보내지 않아요"
        showBack
        fallbackTo={path.my()}
      />

      <div className={styles.page}>
        {notification === null ? (
          <StatusView variant="empty" size="inline" title="보여줄 알림이 없어요" />
        ) : (
          <div className={styles.lock}>
            <p className={styles.date}>{date}</p>
            <p className={styles.time}>{time}</p>

            {notification ? (
              <button type="button" className={styles.card} onClick={() => void open(notification)}>
                <span className={styles.appIcon} aria-hidden="true">
                  f
                </span>
                <span className={styles.cardText}>
                  <span className={styles.app}>feelm · 지금</span>
                  <span className={styles.cardTitle}>{notification.title}</span>
                  <span className={styles.cardBody}>{notification.body}</span>
                </span>
              </button>
            ) : null}
          </div>
        )}
      </div>
    </>
  );
}
