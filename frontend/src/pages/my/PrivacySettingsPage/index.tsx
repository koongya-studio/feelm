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
 * 그 필드 하나만 보낸다.
 *
 * **저장 중에도 칸을 잠그지 않는다**(업무 -854). 한때 저장하는 동안 네 줄을 모두 `disabled`로
 * 잠갔는데, 잠긴 칸이 평소와 똑같이 보여 그 사이의 탭은 말없이 사라졌다 — 눌러도 반응하지
 * 않는 것처럼 보였다. 이제 누른 값은 곧바로 화면에 서고, 요청은 한 번에 하나씩 차례로 보낸다.
 * 요청을 겹치지 않는 까닭은 응답 순서가 뒤바뀌면 옛 값이 이기기 때문이다. 저장하는 동안
 * 같은 줄을 다시 누르면 마지막 값만 이어서 보낸다. 실패하면 그 줄만 서버가 가진 값으로
 * 되돌리고 곧바로 알린다. 알림은 실패한 줄을 다시 저장해 성공할 때까지 남는다 — 다른 줄을
 * 누르거나 저장해도 거두지 않는다. 그러지 않으면 실패가 말없이 묻힌다. 보내는 동안 원래 값으로 되돌려도 그 요청이 끝날 때까지는 저장 중이고,
 * 끝난 뒤 되돌린 값을 이어서 보낸다. 보내는 줄은 이 화면에서 사용자가 고른 줄뿐이다 — 응답에
 * 담긴 다른 줄의 값은 다른 탭 · 기기에서 바꾼 것일 수 있으니 그대로 따른다. 저장 중 · 저장됨은
 * 줄 옆 글자와 `role="status"`로 알린다.
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
import { useEffect, useId, useRef, useState } from 'react';
import Button from '../../../components/Button';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import VisuallyHidden from '../../../components/VisuallyHidden';
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
  {
    key: 'reviewVisibility',
    label: '한줄평 공개',
    note: '개별 한줄평도 공개로 설정해야 다른 사람에게 보여요. 기존 비공개 한줄평은 자동으로 공개되지 않아요',
  },
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
  /** 화면에 선 값. 누르는 즉시 바뀐다 */
  const [settings, setSettings] = useState<PrivacySettings | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [reloadCount, setReloadCount] = useState(0);
  /** 저장하지 못한 줄. 그 줄을 다시 저장해 성공해야 빠진다 */
  const [failedKeys, setFailedKeys] = useState<readonly PrivacyKey[]>([]);
  /** 아직 서버에 닿지 않은 줄. 보내는 중이거나 차례를 기다린다 */
  const [pendingKeys, setPendingKeys] = useState<readonly PrivacyKey[]>([]);
  /** 마지막 저장이 끝났다. 다음에 누르면 지운다 */
  const [saved, setSaved] = useState(false);

  /** 서버가 마지막으로 돌려준 값. 응답이 오면 네 줄 모두 이 값을 따른다 */
  const confirmedRef = useRef<PrivacySettings | null>(null);
  /**
   * 사용자가 이 화면에서 골랐고 아직 저장이 끝나지 않은 줄과 그 값. 여기에 없는 줄은
   * 서버 값을 그대로 보인다 — 다른 탭 · 기기에서 바꾼 값을 미저장 변경으로 오인해
   * 덮어쓰지 않는다
   */
  const dirtyRef = useRef(new Map<PrivacyKey, Visibility>());
  const flushingRef = useRef(false);
  /** 지금 보내고 있는 줄. 응답 전에 원래 값으로 되돌려도 요청은 남아 있다 */
  const inFlightRef = useRef<PrivacyKey | null>(null);
  /** 저장하지 못한 줄. 다른 줄의 입력 · 저장으로는 지우지 않는다 */
  const failedRef = useRef(new Set<PrivacyKey>());
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;

    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((profile) => {
        if (active) {
          const loaded = pickSettings(profile);

          confirmedRef.current = loaded;
          dirtyRef.current.clear();
          setSettings(loaded);
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

  /** 화면에 설 값. 서버 값 위에 사용자가 고른 줄만 얹는다 */
  function displayed(): PrivacySettings | null {
    const confirmed = confirmedRef.current;

    if (confirmed === null) return null;

    return { ...confirmed, ...Object.fromEntries(dirtyRef.current) };
  }

  function unsavedKeys(): PrivacyKey[] {
    return ITEMS.map((item) => item.key).filter((key) => dirtyRef.current.has(key));
  }

  /** 서버가 이미 가진 값을 고른 줄은 보낼 까닭이 없다. 보내고 있는 줄은 응답을 기다린다 */
  function dropSettled() {
    const confirmed = confirmedRef.current;

    if (confirmed === null) return;

    for (const [key, level] of dirtyRef.current) {
      if (key !== inFlightRef.current && level === confirmed[key]) dirtyRef.current.delete(key);
    }
  }

  function showProgress() {
    const next = displayed();

    if (!mountedRef.current || next === null) return;

    setSettings(next);
    const inFlight = inFlightRef.current;
    const unsaved = unsavedKeys();

    setPendingKeys(
      inFlight === null || unsaved.includes(inFlight) ? unsaved : [inFlight, ...unsaved],
    );
    setFailedKeys(ITEMS.map((item) => item.key).filter((key) => failedRef.current.has(key)));
  }

  /** 사용자가 고른 줄을 하나씩 보낸다. 이미 돌고 있으면 그 차례에 맡긴다 */
  async function flush() {
    if (flushingRef.current) return;

    flushingRef.current = true;

    try {
      for (let key = unsavedKeys()[0]; key !== undefined; key = unsavedKeys()[0]) {
        const level = dirtyRef.current.get(key)!;
        // 실패하면 그 사이에 다시 눌렀어도 서버 값으로 돌린다. 무엇이 저장됐는지가 먼저다.
        // 성공했는데 서버가 다른 값을 돌려줘도 그 값을 따른다 — 같은 값을 끝없이 보내지 않는다
        let settle = true;

        inFlightRef.current = key;

        try {
          const profile = await updateMyProfile({ [key]: level });

          // 보내지 않은 줄도 응답을 따른다. 다른 곳에서 바뀌었으면 그 값이 맞다
          confirmedRef.current = pickSettings(profile);
          failedRef.current.delete(key);
          // 보내는 동안 다른 값을 골랐으면 그 값을 이어서 보낸다
          settle = dirtyRef.current.get(key) === level;
        } catch {
          failedRef.current.add(key);
        } finally {
          inFlightRef.current = null;
        }

        if (settle) dirtyRef.current.delete(key);
        dropSettled();
        showProgress();
      }
    } finally {
      flushingRef.current = false;
    }

    if (mountedRef.current) setSaved(failedRef.current.size === 0);
  }

  function change(key: PrivacyKey, level: Visibility) {
    const current = displayed();

    if (current === null || level === current[key]) return;

    dirtyRef.current.set(key, level);
    dropSettled();
    setSaved(false);
    showProgress();
    void flush();
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
            pending={pendingKeys.includes(item.key)}
            onChange={(level) => change(item.key, level)}
          />
        ))}

        {failedKeys.length > 0 ? (
          <p className={styles.error} role="alert">
            저장하지 못했어요. 잠시 후 다시 시도해 주세요
          </p>
        ) : null}

        {/* 읽기 프로그램에 저장 경과를 알린다. 눈에는 줄 옆 글자가 같은 말을 한다 */}
        <VisuallyHidden>
          <span role="status">
            {pendingKeys.length > 0 ? '저장하고 있어요' : saved ? '저장했어요' : ''}
          </span>
        </VisuallyHidden>

        <p className={styles.hint}>비공개 항목은 다른 사용자에게 표시되지 않아요</p>
      </div>
    </>
  );
}

function LevelGroup({
  label,
  note,
  value,
  pending,
  onChange,
}: {
  label: string;
  /** 줄 아래 한 줄 안내 */
  note?: string;
  /** 고른 단계 */
  value: Visibility;
  /** 고른 값이 아직 서버에 닿지 않았다. 칸은 잠그지 않는다 */
  pending: boolean;
  onChange: (level: Visibility) => void;
}) {
  const labelId = useId();
  const noteId = useId();

  return (
    <div className={styles.item}>
      <div className={styles.itemHead}>
        <span id={labelId} className={styles.itemLabel}>
          {label}
        </span>

        {pending ? (
          <span className={styles.saving} aria-hidden="true">
            저장 중
          </span>
        ) : null}
      </div>

      <div
        className={styles.levels}
        role="radiogroup"
        aria-labelledby={labelId}
        aria-describedby={note ? noteId : undefined}
        aria-busy={pending || undefined}
      >
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

      {note ? (
        <p id={noteId} className={styles.hint}>
          {note}
        </p>
      ) : null}
    </div>
  );
}
