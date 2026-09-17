/*
 * 7.8-B 공개 범위 설정
 *
 * 이동표: 7.8 → 7.8-B · 뒤로 가기
 *
 * 요소는 와이어프레임 그대로다 — 항목 넷(프로필 · 취향 필름 · 팝콘 버킷 · 한줄평)마다
 * `전체 공개 · 파티원만 · 비공개` 셋 중 하나, 맨 아래 `비공개 항목은 다른 사용자에게
 * 표시되지 않아요`.
 *
 * **계약이 정한 값은 프로필 하나뿐이다**(API-015 `visibility`, `PUBLIC` · `PRIVATE`).
 * 그 줄만 서버에 저장한다 — 고르는 즉시 `updateMyProfile`로 보내고, 실패하면 고르기 전
 * 값으로 되돌린다. `파티원만`은 계약에 없는 단계라 프로필 줄에서는 고를 수 없다.
 *
 * 취향 필름 · 팝콘 버킷 · 한줄평의 공개 범위는 **API가 없다.** 한때 세션 저장소에 두어
 * 저장된 것처럼 보였는데(팀 결정 2026-09-13) 새로고침하면 사라지는 값이라 설정처럼 보이면
 * 안 된다(업무 -610). 세 줄은 잠그고 준비 중이라고 적는다. 화면 구성은 그대로 두어 계약이
 * 열리면 값만 잇는다.
 *
 * 고른 칸은 7.6 탭과 같은 모양이다 — 흰 면으로 올린다. 와이어프레임의 검정 칸은 주 버튼과
 * 같은 무게라 한 화면에 넷을 둘 수 없다(`design-concept.md` 2.4).
 */
import { useEffect, useId, useState } from 'react';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { failureDescription } from '../../../components/StatusView/failure';
import { getMyProfile, updateMyProfile } from '../../../api/users';
import { path } from '../../../routes/paths';
import styles from './PrivacySettingsPage.module.css';
import type { Visibility } from '../../../types/enums';

/** 와이어프레임 7.8-B의 세 단계. 계약의 `Visibility`는 `PUBLIC` · `PRIVATE` 둘뿐이다 */
type PrivacyLevel = Visibility | 'PARTY';

const LEVELS: { value: PrivacyLevel; label: string }[] = [
  { value: 'PUBLIC', label: '전체 공개' },
  { value: 'PARTY', label: '파티원만' },
  { value: 'PRIVATE', label: '비공개' },
];

/** 계약이 없어 아직 고를 수 없는 세 줄. 와이어프레임의 순서와 문구다 */
const PENDING_ITEMS = ['취향 필름 공개', '팝콘 버킷 공개', '한줄평 공개'];

export default function PrivacySettingsPage() {
  const [visibility, setVisibility] = useState<Visibility | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [reloadCount, setReloadCount] = useState(0);
  const [saveFailed, setSaveFailed] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) {
          setVisibility(profile.visibility);
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

  async function changeVisibility(level: PrivacyLevel) {
    // `PARTY`는 계약에 없다. 잠긴 칸이라 여기까지 오지 않지만 타입으로도 막는다
    if (visibility === null || level === 'PARTY' || level === visibility || saving) return;

    const previous = visibility;

    setVisibility(level);
    setSaveFailed(false);
    setSaving(true);

    try {
      const saved = await updateMyProfile({ visibility: level });

      setVisibility(saved.visibility);
    } catch {
      setVisibility(previous);
      setSaveFailed(true);
    } finally {
      setSaving(false);
    }
  }

  const header = (
    <ScreenHeader
      title="공개 범위 설정"
      subtitle="항목별로 공개 범위를 정할 수 있어요"
      showBack
      fallbackTo={path.settings()}
    />
  );

  if (failure !== null) {
    return (
      <>
        {header}

        <StatusView title="공개 범위를 불러오지 못했어요" description={failureDescription(failure)}>
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
  if (visibility === null) {
    return header;
  }

  return (
    <>
      {header}

      <div className={styles.page}>
        <LevelGroup
          label="프로필 공개"
          value={visibility}
          // 계약의 단계는 둘이다. `파티원만`은 고를 수 없다
          disabledLevels={['PARTY']}
          onChange={(level) => void changeVisibility(level)}
        />

        {PENDING_ITEMS.map((label) => (
          <LevelGroup key={label} label={label} value={null} disabled />
        ))}

        {saveFailed ? (
          <p className={styles.error} role="alert">
            저장하지 못했어요. 잠시 후 다시 시도해 주세요
          </p>
        ) : null}

        <p className={styles.hint}>
          취향 필름 · 팝콘 버킷 · 한줄평의 공개 범위는 준비 중이에요. 지금은 프로필 공개 여부만
          저장돼요
        </p>
        <p className={styles.hint}>비공개 항목은 다른 사용자에게 표시되지 않아요</p>
      </div>
    </>
  );
}

function LevelGroup({
  label,
  value,
  disabled = false,
  disabledLevels = [],
  onChange,
}: {
  label: string;
  /** 고른 단계. `null`이면 아직 정할 수 없는 줄이다 */
  value: PrivacyLevel | null;
  /** 줄 전체가 잠겼는지 (준비 중) */
  disabled?: boolean;
  /** 이 줄에서 고를 수 없는 단계 */
  disabledLevels?: PrivacyLevel[];
  onChange?: (level: PrivacyLevel) => void;
}) {
  const labelId = useId();
  const noteId = useId();

  return (
    <div className={styles.item}>
      <span id={labelId} className={styles.itemLabel}>
        {label}
        {disabled ? (
          <span id={noteId} className={styles.hint}>
            {' '}
            · 준비 중
          </span>
        ) : null}
      </span>

      <div
        className={styles.levels}
        role="radiogroup"
        aria-labelledby={labelId}
        aria-describedby={disabled ? noteId : undefined}
        aria-disabled={disabled || undefined}
      >
        {LEVELS.map((level) => (
          <button
            key={level.value}
            type="button"
            role="radio"
            className={styles.level}
            aria-checked={value === level.value}
            disabled={disabled || disabledLevels.includes(level.value)}
            onClick={() => onChange?.(level.value)}
          >
            {level.label}
          </button>
        ))}
      </div>
    </div>
  );
}
