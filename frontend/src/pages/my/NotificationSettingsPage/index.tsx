/*
 * 7.8-C 알림 설정
 *
 * 이동표: 7.8 → 7.8-C · 뒤로 가기
 *
 * 요소는 와이어프레임 그대로다 — 토글 다섯(감상 확인 · 추천 · 파티 초대 · 리포트 발급 ·
 * 마케팅), 맨 아래 감상 확인 알림이 오는 시점. 와이어프레임의 `OTT 이동 2일 후`는 계약과 달라
 * 계약(클릭 즉시)을 따른다 — 문구와 값은 `utils/watchConfirm.ts` 한 곳이다(업무 -766).
 *
 * 계약(API-017 · API-018)은 `RATING` · `RECOMMENDATION` · `PARTY_INVITE` · `REPORT_ISSUED` ·
 * `MARKETING` 다섯이고 토글 다섯과 하나씩 맞는다(파티 초대는 업무 -857, 리포트 발급은 업무
 * -858). 업무 -610에서 잠갔던 두 줄이 모두 서버에 저장된다.
 *
 * 토글은 누르는 즉시 저장한다. API-018이 전체 교체라 계약의 다섯 값을 모두 보낸다
 * (`api/settings.ts`). 실패하면 누르기 전 값으로 되돌리고 알린다. 저장이 도는 동안 같은
 * 토글을 다시 눌러도 요청이 겹치지 않는다.
 *
 * 맨 위에 두었던 `이 기기로 푸시 받기`(업무 -583)는 걷어냈다(업무 -859). 알림은 앱 안 알림
 * 목록(8.1)으로만 온다.
 */
import { useEffect, useId, useState } from 'react';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { failureDescription } from '../../../components/StatusView/failure';
import Switch from '../../../components/Switch';
import { getNotificationSettings, updateNotificationSettings } from '../../../api/settings';
import { path } from '../../../routes/paths';
import { watchConfirmTiming } from '../../../utils/watchConfirm';
import styles from './NotificationSettingsPage.module.css';
import type { NotificationSettings } from '../../../types/user';

/** 와이어프레임 7.8-C의 순서와 문구 */
const ITEMS: { key: keyof NotificationSettings; label: string }[] = [
  { key: 'RATING', label: '감상 확인 알림' },
  { key: 'RECOMMENDATION', label: '추천 알림' },
  { key: 'PARTY_INVITE', label: '파티 초대 알림' },
  { key: 'REPORT_ISSUED', label: '리포트 발급 알림' },
  { key: 'MARKETING', label: '마케팅 알림' },
];

export default function NotificationSettingsPage() {
  const [settings, setSettings] = useState<NotificationSettings | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [reloadCount, setReloadCount] = useState(0);
  const [saveFailed, setSaveFailed] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;

    getNotificationSettings()
      .then((value) => {
        if (active) {
          setSettings(value);
          setFailure(null);
        }
      })
      .catch((error: unknown) => {
        if (active) setFailure(error);
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  async function toggle(key: keyof NotificationSettings, checked: boolean) {
    if (!settings || saving) return;

    const previous = settings;
    const next = { ...settings, [key]: checked };

    setSettings(next);
    setSaveFailed(false);
    setSaving(true);

    try {
      setSettings(await updateNotificationSettings(next));
    } catch {
      setSettings(previous);
      setSaveFailed(true);
    } finally {
      setSaving(false);
    }
  }

  const header = <ScreenHeader title="알림 설정" showBack fallbackTo={path.settings()} />;

  if (failure !== null) {
    return (
      <>
        {header}

        <StatusView title="알림 설정을 불러오지 못했어요" description={failureDescription(failure)}>
          <Button
            onClick={() => {
              setFailure(null);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  // 데이터가 오기 전에는 아무것도 그리지 않는다(팀 결정 2026-09-08)
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

        <p className={styles.hint}>{watchConfirmTiming()}</p>
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
