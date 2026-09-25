/*
 * 7.8-B 공개 범위 설정
 *
 * 이동표: 7.8 → 7.8-B · 뒤로 가기
 *
 * 요소는 와이어프레임을 따른다 — 항목 넷(프로필 · 취향 필름 · 팝콘 버킷 · 한줄평)마다
 * `전체 공개 · 비공개` 둘 중 하나, 맨 아래 `비공개 항목은 다른 사용자에게 표시되지 않아요`.
 * 와이어프레임의 `파티원만`은 계약에 없어 칸을 그리지 않는다(아래).
 *
 * **네 줄 모두 서버에 저장한다**(업무 -856). 프로필은 API-015 `visibility`, 나머지 셋은
 * `frameVisibility` · `popcornVisibility` · `reviewVisibility`다. 고르는 즉시 `updateMyProfile`로
 * 그 필드 하나만 보내고, 실패하면 고르기 전 값으로 되돌린다. 저장 중에는 다른 칸도 잠가 요청이
 * 겹치지 않게 한다 — 응답 순서가 뒤바뀌면 화면이 옛 값을 보여 준다.
 *
 * 계약의 값은 `PUBLIC` · `PRIVATE` 둘이다. `파티원만`은 계약에 없는 단계라 잠근 칸으로 두지
 * 않고 아예 뺐다(업무 -856 결정 D-1, 사용자 결정 2026-09-25). 계약에 생기면 `LEVELS`에 더한다. 한때 세 줄을 세션 저장소에만 두었다가(2026-09-13) 새로고침하면
 * 사라져 잠갔는데(업무 -610), 이제 API-014가 돌려주므로 다시 들어와도 그대로다.
 *
 * 팝콘 버킷은 비공개여도 함께 있는 파티의 파티원에게는 파티 화면에서 보인다(API-057 파티 예외).
 * 맨 아래 문구와 어긋나지 않게 그 줄에 적는다.
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
import type { MyProfile } from '../../../types/user';

/** 계약의 `Visibility` 두 단계 */
const LEVELS: { value: Visibility; label: string }[] = [
  { value: 'PUBLIC', label: '전체 공개' },
  { value: 'PRIVATE', label: '비공개' },
];

/** 7.8-B의 네 줄이 저장되는 API-015 필드 */
type PrivacyKey = 'visibility' | 'frameVisibility' | 'popcornVisibility' | 'reviewVisibility';

type PrivacySettings = Pick<MyProfile, PrivacyKey>;

/** 와이어프레임의 순서와 문구다 */
const ITEMS: { key: PrivacyKey; label: string; note?: string }[] = [
  { key: 'visibility', label: '프로필 공개' },
  { key: 'frameVisibility', label: '취향 필름 공개' },
  {
    key: 'popcornVisibility',
    label: '팝콘 버킷 공개',
    note: '비공개여도 함께 있는 파티의 파티원에게는 파티 화면에서 보여요',
  },
  { key: 'reviewVisibility', label: '한줄평 공개' },
];

function pickSettings(profile: MyProfile): PrivacySettings {
  return {
    visibility: profile.visibility,
    frameVisibility: profile.frameVisibility,
    popcornVisibility: profile.popcornVisibility,
    reviewVisibility: profile.reviewVisibility,
  };
}

export default function PrivacySettingsPage() {
  const [settings, setSettings] = useState<PrivacySettings | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [reloadCount, setReloadCount] = useState(0);
  const [saveFailed, setSaveFailed] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) {
          setSettings(pickSettings(profile));
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

  async function change(key: PrivacyKey, level: Visibility) {
    if (settings === null || level === settings[key] || saving) return;

    const previous = settings;

    setSettings({ ...settings, [key]: level });
    setSaveFailed(false);
    setSaving(true);

    try {
      const saved = await updateMyProfile({ [key]: level });

      setSettings(pickSettings(saved));
    } catch {
      setSettings(previous);
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
  if (settings === null) {
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
            note={item.note}
            value={settings[item.key]}
            busy={saving}
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
  note,
  value,
  busy,
  onChange,
}: {
  label: string;
  /** 줄 아래 한 줄 안내 */
  note?: string;
  /** 고른 단계 */
  value: Visibility;
  /** 저장 중. 요청이 겹치지 않게 모든 칸을 잠근다 */
  busy: boolean;
  onChange: (level: Visibility) => void;
}) {
  const labelId = useId();
  const noteId = useId();

  return (
    <div className={styles.item}>
      <span id={labelId} className={styles.itemLabel}>
        {label}
      </span>

      <div
        className={styles.levels}
        role="radiogroup"
        aria-labelledby={labelId}
        aria-describedby={note ? noteId : undefined}
        aria-busy={busy || undefined}
      >
        {LEVELS.map((level) => (
          <button
            key={level.value}
            type="button"
            role="radio"
            className={styles.level}
            aria-checked={value === level.value}
            disabled={busy}
            onClick={() => onChange(level.value)}
          >
            {level.label}
          </button>
        ))}
      </div>

      {note ? (
        <p id={noteId} className={styles.hint}>
          {note}
        </p>
      ) : null}
    </div>
  );
}
