/*
 * 7.8-C 알림 설정
 *
 * 이동표: 7.8 → 7.8-C · 뒤로 가기
 *
 * 요소는 와이어프레임 그대로다 — 토글 다섯(감상 확인 · 추천 · 파티 초대 · 리포트 발급 ·
 * 마케팅), 맨 아래 `감상 확인 알림은 OTT 이동 2일 후에 도착해요`.
 *
 * 계약(API-017 · API-018)은 `RATING` · `RECOMMENDATION` · `MARKETING` 셋이다. 파티 초대 ·
 * 리포트 발급은 계약에 없지만 와이어프레임대로 그리기로 했다(팀 결정 2026-09-13).
 * **두 값은 보낼 곳이 없어 세션 저장소에만 남는다**(사용자 결정 2026-09-16). 새로고침하면
 * 계약의 셋은 서버 값 그대로 돌아오고 둘은 기본값으로 돌아간다.
 *
 * 토글은 누르는 즉시 저장한다. API-018이 전체 교체라 계약의 세 값을 모두 보낸다
 * (`api/settings.ts`). 실패하면 누르기 전 값으로 되돌리고 알린다.
 *
 * `[?]` 알림을 실제로 보내는 채널이 요구사항에 없다(API-017 미정 사항). 설정만 저장한다.
 */
import { useEffect, useId, useState } from 'react';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import Switch from '../../../components/Switch';
import { getNotificationSettings, updateNotificationSettings } from '../../../api/settings';
import styles from './NotificationSettingsPage.module.css';
import type { NotificationSettingsView } from '../../../types/unspecified';

/** 와이어프레임 7.8-C의 순서와 문구 */
const ITEMS: { key: keyof NotificationSettingsView; label: string }[] = [
  { key: 'RATING', label: '감상 확인 알림' },
  { key: 'RECOMMENDATION', label: '추천 알림' },
  { key: 'PARTY_INVITE', label: '파티 초대 알림' },
  { key: 'REPORT', label: '리포트 발급 알림' },
  { key: 'MARKETING', label: '마케팅 알림' },
];

export default function NotificationSettingsPage() {
  const [settings, setSettings] = useState<NotificationSettingsView | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [saveFailed, setSaveFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getNotificationSettings()
      .then((value) => {
        if (active) {
          setSettings(value);
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

  async function toggle(key: keyof NotificationSettingsView, checked: boolean) {
    if (!settings) return;

    const previous = settings;
    const next = { ...settings, [key]: checked };

    setSettings(next);
    setSaveFailed(false);

    try {
      await updateNotificationSettings(next);
    } catch {
      setSettings(previous);
      setSaveFailed(true);
    }
  }

  const header = <ScreenHeader title="알림 설정" showBack />;

  if (failed) {
    return (
      <>
        {header}

        <StatusView title="알림 설정을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
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

  if (!settings) {
    return header;
  }

  return (
    <>
      {header}

      <div className={styles.page}>
        <ul className={styles.list}>
          {ITEMS.map((item) => (
            <li key={item.key}>
              <SwitchRow
                label={item.label}
                checked={settings[item.key]}
                onChange={(checked) => void toggle(item.key, checked)}
              />
            </li>
          ))}
        </ul>

        {saveFailed ? (
          <p className={styles.error} role="alert">
            저장하지 못했어요. 잠시 후 다시 시도해 주세요
          </p>
        ) : null}

        <p className={styles.hint}>감상 확인 알림은 OTT 이동 2일 후에 도착해요</p>
      </div>
    </>
  );
}

function SwitchRow({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  const labelId = useId();

  return (
    <div className={styles.row}>
      <span id={labelId} className={styles.label}>
        {label}
      </span>
      <Switch checked={checked} onChange={onChange} labelledBy={labelId} />
    </div>
  );
}
