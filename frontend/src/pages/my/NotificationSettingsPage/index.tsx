/*
 * 7.8-C 알림 설정
 *
 * 이동표: 7.8 → 7.8-C · 뒤로 가기
 *
 * 요소는 와이어프레임 그대로다 — 토글 다섯(감상 확인 · 추천 · 파티 초대 · 리포트 발급 ·
 * 마케팅), 맨 아래 `감상 확인 알림은 OTT 이동 2일 후에 도착해요`.
 *
 * 계약(API-017 · API-018)은 `RATING` · `RECOMMENDATION` · `MARKETING` 셋이다. 파티 초대 ·
 * 리포트 발급은 계약에 없다. 한때 세션 저장소에만 남겨 저장된 것처럼 보였는데, 새로고침하면
 * 사라지는 값이라 설정처럼 보이면 안 된다(업무 -610). **그 둘은 잠그고 준비 중이라고 적는다.**
 * 자리는 와이어프레임대로 두어 계약이 열리면 값만 잇는다.
 *
 * 토글은 누르는 즉시 저장한다. API-018이 전체 교체라 계약의 세 값을 모두 보낸다
 * (`api/settings.ts`). 실패하면 누르기 전 값으로 되돌리고 알린다. 저장이 도는 동안 같은
 * 토글을 다시 눌러도 요청이 겹치지 않는다.
 *
 * `[?]` 알림을 실제로 보내는 채널이 요구사항에 없다(API-017 미정 사항). 설정만 저장한다.
 */
import { useEffect, useId, useState } from 'react';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { failureDescription } from '../../../components/StatusView/failure';
import Switch from '../../../components/Switch';
import { getNotificationSettings, updateNotificationSettings } from '../../../api/settings';
import { path } from '../../../routes/paths';
import styles from './NotificationSettingsPage.module.css';
import type { NotificationSettings } from '../../../types/user';

/** 와이어프레임 7.8-C의 순서와 문구. 계약에 없는 둘은 `key`가 없다 */
const ITEMS: { key: keyof NotificationSettings | null; label: string }[] = [
  { key: 'RATING', label: '감상 확인 알림' },
  { key: 'RECOMMENDATION', label: '추천 알림' },
  { key: null, label: '파티 초대 알림' },
  { key: null, label: '리포트 발급 알림' },
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
          {ITEMS.map((item) =>
            item.key ? (
              <li key={item.key}>
                <SwitchRow
                  label={item.label}
                  checked={settings[item.key]}
                  onChange={(checked) =>
                    void toggle(item.key as keyof NotificationSettings, checked)
                  }
                />
              </li>
            ) : (
              <li key={item.label}>
                <SwitchRow label={item.label} checked={false} pending />
              </li>
            ),
          )}
        </ul>

        {saveFailed ? (
          <p className={styles.error} role="alert">
            저장하지 못했어요. 잠시 후 다시 시도해 주세요
          </p>
        ) : null}

        <p className={styles.hint}>파티 초대 · 리포트 발급 알림은 준비 중이에요</p>
        <p className={styles.hint}>감상 확인 알림은 OTT 이동 2일 후에 도착해요</p>
      </div>
    </>
  );
}

function SwitchRow({
  label,
  checked,
  pending = false,
  onChange,
}: {
  label: string;
  checked: boolean;
  /** 계약이 없어 아직 켤 수 없는 줄. 스위치가 잠긴다 */
  pending?: boolean;
  onChange?: (checked: boolean) => void;
}) {
  const labelId = useId();

  return (
    <div className={styles.row}>
      <span id={labelId} className={styles.label}>
        {label}
        {pending ? <span className={styles.hint}> · 준비 중</span> : null}
      </span>
      <Switch
        checked={checked}
        disabled={pending}
        onChange={(next) => onChange?.(next)}
        labelledBy={labelId}
      />
    </div>
  );
}
