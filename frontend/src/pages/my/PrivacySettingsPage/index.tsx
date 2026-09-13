/*
 * 7.8-B 공개 범위 설정
 *
 * 이동표: 7.8 → 7.8-B · 뒤로 가기
 *
 * 요소는 와이어프레임 그대로다 — 항목 넷(프로필 · 취향 필름 · 팝콘 버킷 · 한줄평)마다
 * `전체 공개 · 파티원만 · 비공개` 셋 중 하나, 맨 아래 `비공개 항목은 다른 사용자에게
 * 표시되지 않아요`.
 *
 * **계약과 다르다.** 계약은 프로필 하나의 `visibility`(`PUBLIC` · `PRIVATE`, API-015)뿐이다.
 * 와이어프레임대로 그리기로 했다(팀 결정 2026-09-13). 값은 목업에만 남는다
 * (`types/unspecified.ts` `PrivacySettingsView`).
 *
 * 저장 버튼이 없다. 와이어프레임에도 없고, 고르는 즉시 저장한다. 실패하면 고르기 전
 * 값으로 되돌리고 알린다.
 *
 * 고른 칸은 7.6 탭과 같은 모양이다 — 흰 면으로 올린다. 와이어프레임의 검정 칸은 주 버튼과
 * 같은 무게라 한 화면에 넷을 둘 수 없다(`design-concept.md` 2.4).
 *
 * `[?]` 여기서 고른 값이 7.2-A · 7.2-B의 공개 프로필에 반영되지 않는다. 그 목업은
 *       `이미 공개된 상태`를 그린 것이다(mocks/publicProfiles.ts)
 */
import { useEffect, useId, useState } from 'react';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getPrivacySettings, updatePrivacySettings } from '../../../api/settings';
import styles from './PrivacySettingsPage.module.css';
import type { PrivacyLevel, PrivacySettingsView } from '../../../types/unspecified';

const ITEMS: { key: keyof PrivacySettingsView; label: string }[] = [
  { key: 'profile', label: '프로필 공개' },
  { key: 'film', label: '취향 필름 공개' },
  { key: 'popcorn', label: '팝콘 버킷 공개' },
  { key: 'review', label: '한줄평 공개' },
];

const LEVELS: { value: PrivacyLevel; label: string }[] = [
  { value: 'PUBLIC', label: '전체 공개' },
  { value: 'PARTY', label: '파티원만' },
  { value: 'PRIVATE', label: '비공개' },
];

export default function PrivacySettingsPage() {
  const [settings, setSettings] = useState<PrivacySettingsView | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);
  const [saveFailed, setSaveFailed] = useState(false);

  useEffect(() => {
    let active = true;

    getPrivacySettings()
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

  async function change(key: keyof PrivacySettingsView, level: PrivacyLevel) {
    if (!settings || settings[key] === level) return;

    const previous = settings;
    const next = { ...settings, [key]: level };

    setSettings(next);
    setSaveFailed(false);

    try {
      await updatePrivacySettings(next);
    } catch {
      setSettings(previous);
      setSaveFailed(true);
    }
  }

  const header = (
    <ScreenHeader title="공개 범위 설정" subtitle="항목별로 공개 범위를 정할 수 있어요" showBack />
  );

  if (failed) {
    return (
      <>
        {header}

        <StatusView title="공개 범위를 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
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
        {ITEMS.map((item) => (
          <LevelGroup
            key={item.key}
            label={item.label}
            value={settings[item.key]}
            onChange={(level) => void change(item.key, level)}
          />
        ))}

        {saveFailed ? (
          <p className={styles.error} role="alert">
            저장하지 못했어요. 잠시 후 다시 시도해 주세요
          </p>
        ) : null}

        <p className={styles.hint}>비공개 항목은 다른 사용자에게 표시되지 않아요</p>
      </div>
    </>
  );
}

function LevelGroup({
  label,
  value,
  onChange,
}: {
  label: string;
  value: PrivacyLevel;
  onChange: (level: PrivacyLevel) => void;
}) {
  const labelId = useId();

  return (
    <div className={styles.item}>
      <span id={labelId} className={styles.itemLabel}>
        {label}
      </span>

      <div className={styles.levels} role="radiogroup" aria-labelledby={labelId}>
        {LEVELS.map((level) => (
          <button
            key={level.value}
            type="button"
            role="radio"
            className={styles.level}
            aria-checked={value === level.value}
            onClick={() => onChange(level.value)}
          >
            {level.label}
          </button>
        ))}
      </div>
    </div>
  );
}
