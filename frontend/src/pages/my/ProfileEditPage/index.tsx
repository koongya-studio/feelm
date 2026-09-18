/*
 * 7.2 프로필 편집 (업무 S15P21E106-616)
 *
 * **프로필 화면 그대로에서 한 항목씩 고친다**(사용자 시안 2026-09-18). 설정 목록처럼 항목명과
 * 화살표를 늘어놓지 않는다. 화면은 7.1 · 7.2-A · 7.2-B와 같은 `ProfileLayout`(`mode="edit"`)이고,
 * 고칠 수 있는 자리마다 연필이 붙는다.
 *
 * | 항목 | 편집 | 저장 |
 * | --- | --- | --- |
 * | 사진 | 시트 → 촬영 · 앨범 → 자르기 / 기본 이미지 / 삭제 | 계약 없음(목업) |
 * | 닉네임 | 그 자리의 입력칸. 규칙 · 중복을 확인한다 | API-015 |
 * | 소개 | 그 자리의 여러 줄 입력. 40자 | 계약 없음(목업) |
 * | 공개된 취향 키워드 | 칩을 눌러 공개할 것을 고른다 | 계약 없음(목업) |
 * | 공개된 취향 필름 | 포스터를 눌러 공개할 것을 고른다 | 계약 없음(목업) |
 *
 * **한 번에 한 항목만 연다.** 고치던 것이 있는데 다른 연필을 누르거나 화면을 떠나려 하면
 * 저장 · 버리기 · 계속 편집을 묻는다(`UnsavedDialog`). 떠나는 것은 라우터가 막고
 * (`useBlocker`), 새로고침 · 탭 닫기는 브라우저 확인창이 막는다.
 *
 * 저장은 **항목별**이다. 성공하면 그 구역만 보기로 돌아가고 짧게 알린다. 실패하면 입력값을
 * 그대로 두고 편집기 아래에 까닭을 적는다. 저장 중에는 그 편집기의 저장 버튼만 잠기고,
 * 같은 요청이 두 번 나가지 않는다.
 *
 * 저장한 값은 7.1과 7.2-A가 다시 읽을 때 그대로 보인다(`api/users.ts`).
 *
 * **닉네임은 입력을 멈추면 서버에 사용 가능한지 물어본다**(API-016, 업무 -391). 확인을
 * 통과해도 저장(API-015)이 다시 검사하므로 `409 NICKNAME_DUPLICATED`는 여전히 올 수 있다.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useBlocker, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ConfirmDialog from '../../../components/ConfirmDialog';
import ProfileLayout, { ProfileSkeleton } from '../../../components/ProfileLayout';
import ScreenHeader from '../../../components/ScreenHeader';
import Snackbar from '../../../components/Snackbar';
import StatusView from '../../../components/StatusView';
import { failureDescription } from '../../../components/StatusView/failure';
import { ApiError } from '../../../api/apiError';
import {
  checkNicknameAvailability,
  getMyProfileView,
  isValidNickname,
  updateMyProfile,
  updateProfileExtras,
} from '../../../api/users';
import { useBack } from '../../../hooks/useBack';
import { path } from '../../../routes/paths';
import { avatarFileProblem, readAsDataUrl } from '../../../utils/avatarImage';
import AvatarCropper from './AvatarCropper';
import AvatarSheet from './AvatarSheet';
import {
  BioEditor,
  FilmsEditor,
  KeywordsEditor,
  NICKNAME_HINT,
  NicknameEditor,
} from './ProfileEditors';
import UnsavedDialog from './UnsavedDialog';
import styles from './ProfileEditPage.module.css';
import type { EditableKey } from '../../../components/ProfileLayout';
import type { ProfileExtrasUpdate, ProfileView } from '../../../types/unspecified';

/** 저장했다는 알림이 떠 있는 시간 */
const NOTICE_MS = 1800;

/**
 * 입력이 멎었다고 보는 시간(ms). 글자마다 부르면 닉네임 하나에 요청이 열 번 나간다.
 */
const NICKNAME_CHECK_DELAY_MS = 400;

type NicknameState = 'checking' | 'available' | 'duplicated' | 'invalid' | 'failed';

const NICKNAME_MESSAGE: Record<NicknameState, string> = {
  checking: '확인하는 중이에요',
  available: '사용할 수 있는 닉네임이에요',
  duplicated: '이미 사용 중인 닉네임이에요',
  invalid: `닉네임은 ${NICKNAME_HINT}예요`,
  failed: '사용 가능한지 확인하지 못했어요',
};

/** 편집기가 들고 있는 초안. 연필을 누를 때 지금 값으로 채운다 */
type Draft = {
  nickname: string;
  bio: string;
  keywordCodes: string[];
  frameIds: number[];
};

function draftOf(profile: ProfileView): Draft {
  return {
    nickname: profile.nickname,
    bio: profile.bio,
    keywordCodes: profile.keywords.filter((item) => item.isPublic).map((item) => item.code),
    frameIds: profile.frames.filter((item) => item.isPublic).map((item) => item.frameId),
  };
}

function sameSet<T>(a: T[], b: T[]): boolean {
  return a.length === b.length && a.every((value) => b.includes(value));
}

function toggled<T>(values: T[], value: T): T[] {
  return values.includes(value) ? values.filter((item) => item !== value) : [...values, value];
}

/** 이 항목의 초안이 저장된 값과 다른지 */
function isDirty(key: EditableKey, draft: Draft, profile: ProfileView): boolean {
  const saved = draftOf(profile);

  if (key === 'nickname') return draft.nickname !== saved.nickname;
  if (key === 'bio') return draft.bio !== saved.bio;
  if (key === 'keywords') return !sameSet(draft.keywordCodes, saved.keywordCodes);

  return !sameSet(draft.frameIds, saved.frameIds);
}

/** 사진 한 장의 진행. 실패하면 다시 올릴 사진을 들고 있는다 — 기존 사진은 그대로다 */
type AvatarState =
  | { status: 'idle' }
  | { status: 'uploading' }
  | { status: 'failed'; next: string | null }
  | { status: 'invalid'; message: string };

/** 연필을 누른 곳 · 떠나려는 곳. 고치던 것이 있으면 여기를 들고 묻는다 */
type Pending = { kind: 'switch'; key: EditableKey } | { kind: 'leave' };

/**
 * 닉네임 사용 가능 확인(API-016). 저장된 닉네임 그대로면 묻지 않는다.
 *
 * 결과는 어느 닉네임에 대한 것인지와 함께 든다 — 입력이 바뀐 뒤 늦게 온 결과를 쓰지 않는다.
 */
function useNicknameCheck(nickname: string, current: string | null, active: boolean) {
  const [result, setResult] = useState<{ nickname: string; state: NicknameState } | null>(null);

  useEffect(() => {
    if (!active || current === null || nickname === current) return;

    let live = true;
    const timer = setTimeout(() => {
      // 형식이 틀린 값은 부르지 않는다. 서버가 `INVALID_FORMAT`으로 같은 답을 준다
      if (!isValidNickname(nickname)) {
        setResult({ nickname, state: 'invalid' });

        return;
      }

      setResult({ nickname, state: 'checking' });

      checkNicknameAvailability(nickname)
        .then(({ available, reason }) => {
          if (!live) return;

          setResult({
            nickname,
            state: available ? 'available' : reason === 'INVALID_FORMAT' ? 'invalid' : 'duplicated',
          });
        })
        .catch(() => {
          if (live) setResult({ nickname, state: 'failed' });
        });
    }, NICKNAME_CHECK_DELAY_MS);

    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [nickname, current, active]);

  return active && nickname !== current && result?.nickname === nickname ? result.state : null;
}

export default function ProfileEditPage() {
  const navigate = useNavigate();
  const finish = useBack(path.my());

  const [profile, setProfile] = useState<ProfileView | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [reloadCount, setReloadCount] = useState(0);

  const [editing, setEditing] = useState<EditableKey | null>(null);
  const [draft, setDraft] = useState<Draft>({
    nickname: '',
    bio: '',
    keywordCodes: [],
    frameIds: [],
  });
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [pending, setPending] = useState<Pending | null>(null);
  const [notice, setNotice] = useState('');

  const [avatar, setAvatar] = useState<AvatarState>({ status: 'idle' });
  const [sheetOpen, setSheetOpen] = useState(false);
  const [cropSrc, setCropSrc] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const cameraRef = useRef<HTMLInputElement>(null);
  const albumRef = useRef<HTMLInputElement>(null);
  /** 저장 요청이 나가 있는지. 상태가 바뀌기 전의 두 번째 누름까지 막으려고 ref로 든다 */
  const inFlight = useRef(false);

  useEffect(() => {
    let active = true;

    getMyProfileView()
      .then((value) => {
        if (active) {
          setProfile(value);
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

  // 알림은 잠깐 떴다가 사라진다
  useEffect(() => {
    if (!notice) return;

    const timer = setTimeout(() => setNotice(''), NOTICE_MS);

    return () => clearTimeout(timer);
  }, [notice]);

  const dirty = Boolean(profile && editing && isDirty(editing, draft, profile));

  const nicknameState = useNicknameCheck(
    draft.nickname,
    profile?.nickname ?? null,
    editing === 'nickname',
  );

  /*
   * 떠나기 전에 묻는다. 경로가 바뀌는 이동만 막는다 — 같은 화면 안의 주소 변화는 떠나는
   * 것이 아니다. 새로고침 · 탭 닫기는 라우터 밖이라 브라우저 확인창에 맡긴다.
   */
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && currentLocation.pathname !== nextLocation.pathname,
  );

  useEffect(() => {
    if (!dirty) return;

    const onBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault();

    window.addEventListener('beforeunload', onBeforeUnload);

    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const open = (key: EditableKey) => {
    if (!profile) return;

    setDraft(draftOf(profile));
    setSaveError('');
    setEditing(key);
  };

  const close = () => {
    setEditing(null);
    setSaveError('');
  };

  const requestEdit = (key: EditableKey) => {
    if (editing && editing !== key && dirty) {
      setPending({ kind: 'switch', key });

      return;
    }

    open(key);
  };

  /** 지금 열린 항목을 저장한다. 성공하면 true — 묻던 창이 다음으로 넘어갈 수 있다 */
  const save = useCallback(async (): Promise<boolean> => {
    if (!profile || !editing || inFlight.current) return false;

    if (!isDirty(editing, draft, profile)) {
      close();

      return true;
    }

    if (editing === 'nickname') {
      if (!isValidNickname(draft.nickname)) {
        setSaveError(NICKNAME_MESSAGE.invalid);

        return false;
      }

      // 이미 서버가 안 된다고 답한 값이다. 보내 봐야 `409`로 돌아온다
      if (nicknameState === 'duplicated') {
        setSaveError(NICKNAME_MESSAGE.duplicated);

        return false;
      }
    }

    inFlight.current = true;
    setSaving(true);
    setSaveError('');

    try {
      if (editing === 'nickname') {
        const saved = await updateMyProfile({ nickname: draft.nickname });

        setProfile((prev) => (prev ? { ...prev, nickname: saved.nickname } : prev));
      } else {
        const update: ProfileExtrasUpdate =
          editing === 'bio'
            ? { bio: draft.bio.trim() }
            : editing === 'keywords'
              ? { publicKeywordCodes: draft.keywordCodes }
              : { publicFrameIds: draft.frameIds };

        setProfile(await updateProfileExtras(update));
      }

      setEditing(null);
      setNotice('저장했어요');

      return true;
    } catch (error: unknown) {
      setSaveError(
        error instanceof ApiError && error.status < 500 && error.message
          ? error.message
          : `저장하지 못했어요. ${failureDescription(error)}`,
      );

      return false;
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  }, [profile, editing, draft, nicknameState]);

  /** 지금 묻고 있는 것. 떠나려다 막힌 것이 먼저다 */
  const asking: Pending | null = blocker.state === 'blocked' ? { kind: 'leave' } : pending;

  /** 묻는 창을 닫고 제자리에 머문다 */
  const stay = () => {
    setPending(null);
    if (blocker.state === 'blocked') blocker.reset();
  };

  /** 묻는 창에서 고른 다음 걸음 */
  const proceed = (next: Pending) => {
    setPending(null);

    if (next.kind === 'switch') {
      open(next.key);

      return;
    }

    close();
    if (blocker.state === 'blocked') blocker.proceed();
  };

  /** 사진을 올리거나(`data URL`) 기본으로 돌린다(`null`). 실패해도 기존 사진은 그대로다 */
  const uploadAvatar = async (next: string | null, done: string) => {
    if (avatar.status === 'uploading') return;

    setAvatar({ status: 'uploading' });

    try {
      const saved = await updateProfileExtras({ avatarUrl: next });

      // 사진만 바꾼다. 다른 항목을 고치던 중이면 그 초안은 건드리지 않는다
      setProfile((prev) => (prev ? { ...prev, avatarUrl: saved.avatarUrl } : prev));
      setAvatar({ status: 'idle' });
      setNotice(done);
    } catch {
      setAvatar({ status: 'failed', next });
    }
  };

  const pickFile = async (file: File | undefined) => {
    if (!file) return;

    const problem = avatarFileProblem(file);

    if (problem) {
      setAvatar({ status: 'invalid', message: problem });

      return;
    }

    try {
      setAvatar({ status: 'idle' });
      setCropSrc(await readAsDataUrl(file));
    } catch {
      setAvatar({ status: 'invalid', message: '사진을 읽지 못했어요. 다른 사진을 골라 주세요' });
    }
  };

  const header = (
    <ScreenHeader
      title="프로필 편집"
      showBack
      fallbackTo={path.my()}
      action={
        profile ? (
          <button type="button" className={styles.doneButton} onClick={finish}>
            완료
          </button>
        ) : null
      }
    />
  );

  if (failure !== null) {
    return (
      <>
        {header}

        <StatusView title="프로필을 불러오지 못했어요" description={failureDescription(failure)}>
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

  const actions = { saving, onCancel: close, error: saveError };

  const editor = (() => {
    if (!profile || !editing) return null;

    if (editing === 'nickname') {
      const blocking = nicknameState === 'duplicated' || nicknameState === 'invalid';

      return (
        <NicknameEditor
          {...actions}
          value={draft.nickname}
          onChange={(nickname) => {
            setDraft((prev) => ({ ...prev, nickname }));
            setSaveError('');
          }}
          onSave={() => void save()}
          hint={nicknameState && !blocking ? NICKNAME_MESSAGE[nicknameState] : undefined}
          error={saveError || (blocking ? NICKNAME_MESSAGE[nicknameState] : undefined)}
        />
      );
    }

    if (editing === 'bio') {
      return (
        <BioEditor
          {...actions}
          value={draft.bio}
          onChange={(bio) => setDraft((prev) => ({ ...prev, bio }))}
          onSave={() => void save()}
        />
      );
    }

    if (editing === 'keywords') {
      return (
        <KeywordsEditor
          {...actions}
          keywords={profile.keywords}
          selected={draft.keywordCodes}
          onToggle={(code) =>
            setDraft((prev) => ({ ...prev, keywordCodes: toggled(prev.keywordCodes, code) }))
          }
          onSave={() => void save()}
        />
      );
    }

    return (
      <FilmsEditor
        {...actions}
        frames={profile.frames}
        selected={draft.frameIds}
        onToggle={(frameId) =>
          setDraft((prev) => ({ ...prev, frameIds: toggled(prev.frameIds, frameId) }))
        }
        onSave={() => void save()}
      />
    );
  })();

  const avatarNote =
    avatar.status === 'uploading' ? (
      <p className={styles.avatarStatus} role="status">
        사진을 올리는 중이에요
      </p>
    ) : avatar.status === 'failed' ? (
      <p className={styles.avatarStatus} role="alert">
        사진을 올리지 못했어요. 기존 사진은 그대로예요.{' '}
        <button
          type="button"
          className={styles.inlineRetry}
          onClick={() => void uploadAvatar(avatar.next, '프로필 사진을 바꿨어요')}
        >
          다시 시도
        </button>
      </p>
    ) : avatar.status === 'invalid' ? (
      <p className={styles.avatarStatus} role="alert">
        {avatar.message}
      </p>
    ) : null;

  return (
    <>
      {header}

      <div className={styles.page}>
        {profile ? (
          <ProfileLayout
            mode="edit"
            profile={profile}
            editing={editing && editor ? { key: editing, node: editor } : null}
            onEdit={requestEdit}
            onAvatar={() => setSheetOpen(true)}
            avatarBusy={avatar.status === 'uploading'}
            avatarNote={avatarNote}
          >
            <Button variant="secondary" onClick={() => void navigate(path.profilePreview())}>
              내 공개 프로필 미리보기
            </Button>
          </ProfileLayout>
        ) : (
          <ProfileSkeleton />
        )}
      </div>

      {/* 사진 고르기. 화면에는 보이지 않고 시트의 두 버튼이 연다 */}
      <input
        ref={cameraRef}
        className={styles.fileInput}
        type="file"
        accept="image/*"
        capture="user"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          void pickFile(event.target.files?.[0]);
          event.target.value = '';
        }}
      />
      <input
        ref={albumRef}
        className={styles.fileInput}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        tabIndex={-1}
        aria-hidden="true"
        data-testid="avatar-album-input"
        onChange={(event) => {
          void pickFile(event.target.files?.[0]);
          event.target.value = '';
        }}
      />

      {sheetOpen && profile ? (
        <AvatarSheet
          hasPhoto={Boolean(profile.avatarUrl)}
          onCamera={() => {
            setSheetOpen(false);
            cameraRef.current?.click();
          }}
          onAlbum={() => {
            setSheetOpen(false);
            albumRef.current?.click();
          }}
          onDefault={() => {
            setSheetOpen(false);
            void uploadAvatar(null, '기본 이미지로 바꿨어요');
          }}
          onDelete={() => {
            setSheetOpen(false);
            setConfirmDelete(true);
          }}
          onClose={() => setSheetOpen(false)}
        />
      ) : null}

      {cropSrc ? (
        <AvatarCropper
          src={cropSrc}
          onCancel={() => setCropSrc(null)}
          onApply={(dataUrl) => {
            setCropSrc(null);
            void uploadAvatar(dataUrl, '프로필 사진을 바꿨어요');
          }}
        />
      ) : null}

      {confirmDelete ? (
        <ConfirmDialog
          title="현재 사진을 삭제할까요?"
          description="삭제하면 기본 이미지로 보여요."
          confirmLabel="삭제"
          onCancel={() => setConfirmDelete(false)}
          onConfirm={() => {
            setConfirmDelete(false);
            void uploadAvatar(null, '프로필 사진을 삭제했어요');
          }}
        />
      ) : null}

      {asking ? (
        <UnsavedDialog
          saving={saving}
          saveLabel={asking.kind === 'leave' ? '저장하고 나가기' : '저장하고 넘어가기'}
          discardLabel={asking.kind === 'leave' ? '저장하지 않고 나가기' : '저장하지 않기'}
          onSave={() => {
            void save().then((ok) => {
              // 저장이 실패하면 제자리에 머물러 편집기 아래에 까닭을 보여준다
              if (ok) proceed(asking);
              else stay();
            });
          }}
          onDiscard={() => proceed(asking)}
          onKeep={stay}
        />
      ) : null}

      {notice ? (
        <div className={styles.snackbarSlot}>
          <Snackbar>{notice}</Snackbar>
        </div>
      ) : null}
    </>
  );
}
