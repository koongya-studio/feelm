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
 * 쪽을 가장 강한 버튼으로 두지 않는다. `탈퇴하기`는 체크하고 본인 확인 수단을 갖추기 전에는
 * 누를 수 없고, 누르면 확인 창이 한 번 더 뜬다.
 *
 * 사라지는 수치는 API-014 `stats`에서 읽는다(프레임 · 팝콘 · 평가). 와이어프레임의
 * `평가 96개`는 같은 사용자의 100과 어긋나 쓰지 않았다(screen-spec-my.md 3.1).
 *
 * **소셜 전용 계정은 연결된 제공자로 본인을 확인한다**(API-013 소셜 재인증 경로, 업무 -435 ·
 * -754). 비밀번호 칸 대신 안내가 서고, 확인 창에서 `탈퇴`를 누르면 제공자 로그인으로 나간다.
 * 돌아오면 콜백(`OAuthCallbackPage`)이 인가 코드를 라우터 state로 이 화면에 넘기고
 * (`reauth.ts`), 이 화면이 확인 체크를 되살린 채 API-013을 **한 번** 부른다. 코드는 1회용이라
 * 부르자마자 state를 지워 새로고침 · 뒤로 가기로 같은 코드를 다시 보내지 않는다.
 *
 * `[?]` 탈퇴한 뒤 어디로 가는지 이동표에 없다. 0.1 시작 화면으로 보낸다
 * `[?]` 확인 창 문구는 와이어프레임에 없다
 */
import { useEffect, useEffectEvent, useId, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import ConfirmDialog from '../../../components/ConfirmDialog';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { withdraw } from '../../../api/settings';
import type { WithdrawProof } from '../../../api/settings';
import { getMyProfile } from '../../../api/users';
import { useAuth } from '../../../contexts/authContext';
import { useBack } from '../../../hooks/useBack';
import { forgetWebPush } from '../../../pwa/webPush';
import { path } from '../../../routes/paths';
import {
  isOAuthConfigured,
  SOCIAL_PROVIDER_LABEL,
  startOAuth,
  toSocialProvider,
} from '../../../utils/oauth';
import { readWithdrawReauth } from './reauth';
import styles from './WithdrawPage.module.css';
import type { SocialProvider } from '../../../types/auth';
import type { MyProfile } from '../../../types/user';

/** 비밀번호 경로에서 서버 문구 대신 화면이 정하는 경우만 둔다 */
const PASSWORD_MESSAGE: Record<string, string> = {
  INVALID_CREDENTIALS: '비밀번호가 올바르지 않아요.',
  PASSWORD_NOT_SET: '소셜 계정으로 가입해 비밀번호가 없어요. 소셜 계정으로 본인을 확인해 주세요.',
};

/** 소셜 재인증 경로의 실패. 계약의 오류 코드마다 무엇을 하면 되는지 적는다(API-013) */
const SOCIAL_MESSAGE: Record<string, string> = {
  INVALID_CREDENTIALS:
    '지금 로그인한 계정과 연결된 소셜 계정이 아니에요. 가입할 때 쓴 계정으로 다시 확인해 주세요.',
  UNSUPPORTED_PROVIDER: '지금은 이 소셜 계정으로 본인을 확인할 수 없어요.',
  OAUTH_CODE_INVALID: '본인 확인이 끝나지 않았어요. 다시 시도해 주세요.',
  VALIDATION_FAILED: '본인 확인 설정이 올바르지 않아요. 잠시 후 다시 시도해 주세요.',
  OAUTH_PROVIDER_ERROR: '소셜 계정 제공자와 연결하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

const REAUTH_CANCELLED = '본인 확인을 취소해 탈퇴하지 않았어요.';

export default function WithdrawPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const back = useBack(path.accountManage());
  const { signOut } = useAuth();
  const checkId = useId();

  /*
   * 소셜 본인 확인에서 돌아왔는지. 처음 그릴 때 한 번만 읽는다 — 읽은 뒤 state를 지우므로
   * 다음 렌더에서는 비어 있다.
   */
  const [reauth] = useState(() => readWithdrawReauth(location.state));
  const returnedWithCode = reauth !== null && !('cancelled' in reauth);

  /** 수치를 못 읽어도 탈퇴 안내는 해야 하므로 실패하면 수치 없는 문장으로 쓴다 */
  const [profile, setProfile] = useState<MyProfile | null>(null);
  const [password, setPassword] = useState('');
  // 본인 확인을 다녀왔다면 확인 체크는 이미 한 것이다
  const [acknowledged, setAcknowledged] = useState(reauth !== null);
  const [confirming, setConfirming] = useState(false);
  const [withdrawing, setWithdrawing] = useState(returnedWithCode);
  const [failure, setFailure] = useState<string | null>(
    reauth && 'cancelled' in reauth ? REAUTH_CANCELLED : null,
  );

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
  const linked = (profile?.linkedProviders ?? [])
    .map(toSocialProvider)
    .filter((value): value is SocialProvider => value !== null);
  /** 본인 확인에 쓸 제공자. 이 앱이 인가를 시작할 수 있는 첫 번째 연결이다 */
  const provider = linked.find(isOAuthConfigured) ?? null;
  const providerLabel = SOCIAL_PROVIDER_LABEL[provider ?? linked[0] ?? 'google'];

  /** 탈퇴를 누른 자리. 잠그고 지난 안내를 걷은 뒤 보낸다 */
  function withdrawWith(proof: WithdrawProof, messages: Record<string, string>) {
    setWithdrawing(true);
    setFailure(null);
    void submit(proof, messages);
  }

  /** API-013을 부르고 결과에 따라 안내하거나 세션을 끊는다 */
  async function submit(proof: WithdrawProof, messages: Record<string, string>) {
    try {
      await withdraw(proof);
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';
      const fallback =
        error instanceof ApiError
          ? error.message
          : '탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요.';

      setWithdrawing(false);
      setConfirming(false);
      setFailure(messages[code] ?? fallback);

      return;
    }

    /*
     * 이 기기의 푸시 구독을 지운다(업무 -583). 서버 해제(API-077)는 활성 회원만 받아 탈퇴한
     * 뒤에는 보낼 수 없으므로 기기 쪽만 지운다. 기기 구독이 없으면 푸시가 올 곳도 없다.
     */
    await forgetWebPush();

    // 계정이 사라졌으니 세션도 끊는다. 서버가 쿠키를 지우지만(API-013) 화면 상태는 여기서 바꾼다
    try {
      await signOut();
    } finally {
      void navigate(path.authStart(), { replace: true });
    }
  }

  /*
   * 소셜 본인 확인에서 돌아왔을 때 한 번. 주소의 state부터 지운다 — 코드는 1회용이라 새로고침 ·
   * 뒤로 가기로 같은 코드를 다시 보내면 안 되고, 취소 안내도 다시 뜨면 안 된다. 코드가 있으면
   * 탈퇴를 부른다. 잠금(`withdrawing`)과 안내는 처음 그릴 때 이미 그 상태로 시작했다.
   *
   * StrictMode는 효과를 두 번 돌린다. 두 번째가 같은 코드를 다시 보내지 않게 `useRef`로 막는다
   * (`OAuthCallbackPage`와 같은 이유).
   */
  const handled = useRef(false);

  const onReturned = useEffectEvent(() => {
    if (!reauth) return;

    void navigate(location.pathname, { replace: true, state: null });

    if (!('cancelled' in reauth)) void submit(reauth, SOCIAL_MESSAGE);
  });

  useEffect(() => {
    if (handled.current) return;

    handled.current = true;
    onReturned();
  }, []);

  function confirmWithdraw() {
    if (!socialOnly) {
      void withdrawWith({ password }, PASSWORD_MESSAGE);

      return;
    }

    if (!provider) return;

    // 제공자 로그인으로 나간다. 돌아오면 콜백이 이 화면으로 다시 보낸다
    setWithdrawing(true);
    startOAuth(provider, 'withdraw');
  }

  const stats = profile?.stats ?? null;
  const losses = stats
    ? [
        `취향 필름 ${stats.frameCount}프레임과 팝콘 ${stats.popcornCount}알이 모두 삭제돼요`,
        `평가 ${stats.ratingCount}개와 한줄평이 삭제돼요`,
      ]
    : ['취향 필름과 팝콘이 모두 삭제돼요', '평가와 한줄평이 삭제돼요'];

  const socialNotice = provider
    ? `${providerLabel} 계정으로 가입해 비밀번호가 없어요. 탈퇴하기를 누르면 ${providerLabel}에서 본인을 확인한 뒤 탈퇴해요.`
    : `${providerLabel}에서 본인을 확인해야 탈퇴할 수 있는데, 지금은 확인을 쓸 수 없어요. 잠시 후 다시 시도해 주세요.`;

  const ready = socialOnly ? provider !== null : password !== '';

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
        <p className={styles.notice}>{socialNotice}</p>
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

      {returnedWithCode && withdrawing ? (
        <p className={styles.notice} role="status">
          본인 확인을 마쳤어요. 탈퇴하는 중이에요…
        </p>
      ) : null}

      <div className={styles.actions}>
        {/* 뒤로 가기다. 7.8 · 7.8-A 어디서 왔든 온 곳으로 돌아간다 */}
        <Button disabled={withdrawing} onClick={back}>
          계속 이용하기
        </Button>

        <Button
          variant="secondary"
          disabled={!acknowledged || !ready || withdrawing}
          onClick={() => setConfirming(true)}
        >
          탈퇴하기
        </Button>
      </div>

      <p className={styles.hint}>탈퇴하기를 누르면 한 번 더 확인해요</p>

      {confirming ? (
        <ConfirmDialog
          title="Feelm을 탈퇴할까요?"
          description={
            socialOnly
              ? `${providerLabel}에서 본인을 확인하면 계정과 모든 기록이 바로 삭제돼요`
              : '계정과 모든 기록이 바로 삭제돼요'
          }
          confirmLabel="탈퇴"
          busy={withdrawing}
          onConfirm={confirmWithdraw}
          onCancel={() => setConfirming(false)}
        />
      ) : null}
    </div>
  );
}
