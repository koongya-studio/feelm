/*
 * 7.8-A-1 회원 탈퇴 확인
 *
 * 이동표: 7.8-A → 7.8-A-1 회원 탈퇴 · 7.8-A-1 ⇢ 7.8-A 계속 이용하기
 *
 * 요소는 와이어프레임 그대로다 — 경고 표식, `정말 탈퇴하시겠어요?`, 사라지는 것 세 줄,
 * `위 내용을 모두 확인했어요` 체크, `계속 이용하기`(주 동작) · `탈퇴하기`,
 * `탈퇴하기를 누르면 한 번 더 확인해요`.
 *
 * **비밀번호 입력 한 칸은 와이어프레임에 없이 더했다**(CR-24, 업무 S15P21E106-480).
 * API-013이 본인 확인으로 현재 비밀번호를 받는다. 되돌릴 수 없는 동작이라 계약에서
 * 재인증을 덜어내지 않고 화면을 계약에 맞췄다(사용자 결정 2026-09-17).
 *
 * **주 동작은 `계속 이용하기`다.** 와이어프레임도 그쪽을 검정으로 칠했다. 되돌릴 수 없는
 * 쪽을 가장 강한 버튼으로 두지 않는다. `탈퇴하기`는 체크하고 비밀번호를 적기 전에는
 * 누를 수 없고, 누르면 확인 창이 한 번 더 뜬다.
 *
 * 사라지는 수치는 API-014 `stats`에서 읽는다(프레임 · 팝콘 · 평가). 와이어프레임의
 * `평가 96개`는 같은 사용자의 100과 어긋나 쓰지 않았다(screen-spec-my.md 3.1).
 *
 * **소셜 전용 계정은 아직 탈퇴할 수 없다.** 본인 확인 수단이 정해지지 않아 백엔드가
 * `409 PASSWORD_NOT_SET`으로 거절한다(API-013, 업무 S15P21E106-435). 7.8-A에서 줄을
 * 감추지 않고 이 화면까지 들어오게 둔 뒤 여기서 이유를 알린다 — 탈퇴는 요구사항이
 * 보장하는 경로라(FR-37) 말없이 사라지면 안 된다.
 *
 * `[?]` 탈퇴한 뒤 어디로 가는지 이동표에 없다. 0.1 시작 화면으로 보낸다
 * `[?]` 확인 창 문구는 와이어프레임에 없다
 */
import { useEffect, useId, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ConfirmDialog from '../../../components/ConfirmDialog';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { withdraw } from '../../../api/settings';
import { getMyProfile } from '../../../api/users';
import { useAuth } from '../../../contexts/authContext';
import { useBack } from '../../../hooks/useBack';
import { path } from '../../../routes/paths';
import styles from './WithdrawPage.module.css';
import type { MyProfile } from '../../../types/user';

/** 서버 문구 대신 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  INVALID_CREDENTIALS: '비밀번호가 올바르지 않아요.',
  PASSWORD_NOT_SET: '소셜 계정으로 가입해 비밀번호가 없어요. 아직 탈퇴할 수 없어요.',
};

const SOCIAL_ONLY_NOTICE =
  '소셜 계정으로 가입해 비밀번호가 없어요. 본인 확인 방법이 준비되면 탈퇴할 수 있어요.';

export default function WithdrawPage() {
  const navigate = useNavigate();
  const back = useBack(path.accountManage());
  const { signOut } = useAuth();
  const checkId = useId();

  /** 수치를 못 읽어도 탈퇴 안내는 해야 하므로 실패하면 수치 없는 문장으로 쓴다 */
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [password, setPassword] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    getMyProfile()
      .then((value) => {
        if (active) setProfile(value);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  /*
   * 프로필을 못 읽었으면 비밀번호를 받는 쪽으로 둔다. 입력을 감추면 이메일 계정이
   * 탈퇴할 방법을 잃지만, 두고 보내면 소셜 계정은 서버가 409로 가려 준다.
   */
  const socialOnly = profile?.hasPassword === false;

  async function handleWithdraw() {
    setWithdrawing(true);
    setFailure(null);

    try {
      await withdraw(password);
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';
      const fallback =
        error instanceof ApiError
          ? error.message
          : '탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요.';

      setWithdrawing(false);
      setConfirming(false);
      setFailure(MESSAGE[code] ?? fallback);

      return;
    }

    // 계정이 사라졌으니 세션도 끊는다. 서버가 쿠키를 지우지만(API-013) 화면 상태는 여기서 바꾼다
    try {
      await signOut();
    } finally {
      void navigate(path.authStart(), { replace: true });
    }
  }

  const stats = profile?.stats ?? null;
  const losses = stats
    ? [
        `취향 필름 ${stats.frameCount}프레임과 팝콘 ${stats.popcornCount}알이 모두 삭제돼요`,
        `평가 ${stats.ratingCount}개와 한줄평이 삭제돼요`,
      ]
    : ['취향 필름과 팝콘이 모두 삭제돼요', '평가와 한줄평이 삭제돼요'];

  return (
    <div className={styles.page}>
      <span className={styles.mark} aria-hidden="true">
        !
      </span>

      <h1 className={styles.title}>정말 탈퇴하시겠어요?</h1>

      <ul className={styles.losses}>
        {[...losses, '삭제된 데이터는 복구할 수 없어요'].map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>

      <label htmlFor={checkId} className={styles.check}>
        <input
          id={checkId}
          type="checkbox"
          className={styles.checkbox}
          checked={acknowledged}
          disabled={withdrawing}
          onChange={(event) => setAcknowledged(event.target.checked)}
        />
        위 내용을 모두 확인했어요
      </label>

      {socialOnly ? (
        <p className={styles.notice}>{SOCIAL_ONLY_NOTICE}</p>
      ) : (
        <div className={styles.password}>
          <TextField
            label="현재 비밀번호"
            type="password"
            placeholder="현재 비밀번호"
            autoComplete="current-password"
            value={password}
            disabled={withdrawing}
            onChange={(event) => setPassword(event.target.value)}
            hint="본인 확인을 위해 로그인 비밀번호를 적어 주세요"
          />
        </div>
      )}

      {failure ? (
        <p className={styles.error} role="alert">
          {failure}
        </p>
      ) : null}

      <div className={styles.actions}>
        {/* 뒤로 가기다. 7.8 · 7.8-A 어디서 왔든 온 곳으로 돌아간다 */}
        <Button disabled={withdrawing} onClick={back}>
          계속 이용하기
        </Button>

        <Button
          variant="secondary"
          disabled={!acknowledged || password === '' || withdrawing || socialOnly}
          onClick={() => setConfirming(true)}
        >
          탈퇴하기
        </Button>
      </div>

      <p className={styles.hint}>탈퇴하기를 누르면 한 번 더 확인해요</p>

      {confirming ? (
        <ConfirmDialog
          title="Feelm을 탈퇴할까요?"
          description="계정과 모든 기록이 바로 삭제돼요"
          confirmLabel="탈퇴"
          busy={withdrawing}
          onConfirm={() => void handleWithdraw()}
          onCancel={() => setConfirming(false)}
        />
      ) : null}
    </div>
  );
}
