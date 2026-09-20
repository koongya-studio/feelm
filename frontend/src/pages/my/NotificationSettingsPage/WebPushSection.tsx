/*
 * 7.8-C 맨 위 `이 기기로 푸시 받기` (업무 S15P21E106-583).
 *
 * 알림 종류 토글(API-017 · 018)과 다른 층이다. 종류 토글은 **무엇을** 알릴지를 서버 계정에
 * 저장하고, 이 스위치는 **이 기기로** 받을지를 브라우저 구독(API-075~077)으로 정한다. 그래서
 * 다른 줄과 상자를 나눈다.
 *
 * 켤 수 없는 이유(권한 거부 · 미지원 · iOS 홈 화면 앱 아님 · 서버가 꺼 둠)는 스위치를 잠그고
 * 아래에 적는다. 연결이 끊긴 구독(`expired`)은 다시 켜도록 안내한다.
 */
import { useEffect, useId, useState } from 'react';
import Switch from '../../../components/Switch';
import { disableWebPush, enableWebPush, getWebPushStatus } from '../../../pwa/webPush';
import styles from './NotificationSettingsPage.module.css';
import type { WebPushStatus } from '../../../pwa/webPush';

/** 상태마다 스위치 아래에 적는 말. 켤 수 없는 상태는 스위치를 잠근다 */
const STATUS: Record<WebPushStatus, { note: string; locked: boolean }> = {
  on: { note: '파티 초대와 초대 수락을 이 기기로 바로 알려 드려요', locked: false },
  off: { note: '켜면 파티 초대와 초대 수락을 이 기기로 알려 드려요', locked: false },
  expired: { note: '이 기기와 푸시 연결이 끊겼어요. 다시 켜 주세요', locked: false },
  denied: {
    note: '브라우저에서 이 사이트의 알림을 막아 두었어요. 브라우저 설정에서 허용해 주세요',
    locked: true,
  },
  'install-required': {
    note: '아이폰 · 아이패드는 홈 화면에 추가한 Feelm 앱에서 푸시를 받을 수 있어요',
    locked: true,
  },
  unsupported: { note: '이 브라우저는 푸시 알림을 지원하지 않아요', locked: true },
  unavailable: { note: '지금은 푸시 알림을 보낼 수 없어요', locked: true },
};

export default function WebPushSection() {
  const labelId = useId();
  const noteId = useId();
  const [status, setStatus] = useState<WebPushStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getWebPushStatus()
      .then((value) => {
        if (active) setStatus(value);
      })
      .catch(() => {
        if (active) setStatus('unsupported');
      });

    return () => {
      active = false;
    };
  }, []);

  async function toggle(checked: boolean) {
    if (busy) return;

    setBusy(true);
    setFailed(false);

    try {
      if (checked) {
        setStatus(await enableWebPush());
      } else {
        await disableWebPush();
        setStatus('off');
      }
    } catch {
      setFailed(true);
      setStatus(await getWebPushStatus().catch(() => 'unsupported' as const));
    } finally {
      setBusy(false);
    }
  }

  // 상태를 읽기 전에는 그리지 않는다. 꺼진 스위치가 잠깐 보였다가 켜지면 안 된다
  if (status === null) return null;

  const { note, locked } = STATUS[status];

  return (
    <section className={styles.push} aria-labelledby={labelId}>
      <div className={styles.list}>
        <div className={styles.row}>
          <span className={styles.pushText}>
            <span id={labelId} className={styles.label}>
              이 기기로 푸시 받기
            </span>
            <span id={noteId} className={styles.hint}>
              {note}
            </span>
          </span>
          <Switch
            checked={status === 'on'}
            disabled={locked || busy}
            onChange={(next) => void toggle(next)}
            labelledBy={labelId}
            describedBy={noteId}
          />
        </div>
      </div>

      {failed ? (
        <p className={styles.error} role="alert">
          푸시 설정을 바꾸지 못했어요. 잠시 후 다시 시도해 주세요
        </p>
      ) : null}
    </section>
  );
}
