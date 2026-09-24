/*
 * 0.6 로그인
 *
 * 이동표
 * 0.6 ⇢ 0.7 아이디 찾기 · 비밀번호 찾기
 * 0.6 ⇢ 1.1 신규 사용자가 처음 로그인한 경우
 * 0.6 → 2.1 취향 설정을 완료한 기존 사용자 로그인
 *
 * 신규와 기존을 가르는 것은 초기 취향 설정을 마쳤는지다. 목업 프로필이 아니라 로그인
 * 응답의 `onboardingCompleted`로 판단한다(API-004 처리 규칙).
 *
 * 실패는 둘을 구분한다. 자격 증명이 틀린 것(`INVALID_CREDENTIALS`)은 다시 입력하면 되고,
 * 시도 제한(`RATE_LIMITED`)은 기다리는 것 말고 할 일이 없다. 둘 다 같은 문구를 보여 주면
 * 사용자가 다음에 무엇을 해야 할지 알 수 없다.
 *
 * **미인증 계정 분기는 없앴다**(S15P21E106-601 · -637). 가입이 인증번호로 이메일 소유를
 * 먼저 확인하게 되면서 계정은 만들어지는 순간 `ACTIVE`이고, API-004도 `403
 * EMAIL_NOT_VERIFIED` 대신 `ACTIVE`가 아닌 계정을 모두 `401 INVALID_CREDENTIALS`로 묶는다.
 *
 * 와이어프레임의 도움말은 `아이디 찾기` · `비밀번호 찾기` · `회원가입` 셋이었지만
 * **`아이디 찾기`는 뺐다** (CR-25, 팀 결정 2026-09-17, 업무 S15P21E106-455). 아이디가
 * 곧 이메일이라 찾을 것이 없고 대응 API도 계약에 없다. 0.7은 비밀번호 찾기 하나만
 * 하므로 탭을 고를 필요도 없어졌다.
 *
 * 소셜 로그인은 제공자 동의 화면으로 나갔다가 콜백으로 돌아온다(API-007). 연결된 계정이
 * 없으면 로그인이 아니라 가입이 남은 것이라 콜백이 0.3-A로 보낸다(S15P21E106-499).
 * client-id가 설정되지 않은 제공자는 눌러도 `UNSUPPORTED_PROVIDER`라 버튼을 잠근다.
 *
 * **이미 로그인한 사람은 앱으로 보낸다**(업무 -771, 2026-09-21 배포본 점검). 전에는 로그인한
 * 채로 `/login`에 들어와도 로그인 화면이 그대로 떴다. 로그인 뒤 가는 곳과 같은 규칙이다 —
 * 온보딩을 안 마쳤으면 1.1, 아니면 가려던 주소나 3.1. 세션을 되살리는 중(`restoring`)에는
 * 판단하지 않고 화면을 그대로 둔다. 이 화면에서 방금 로그인한 경우는 `submit`이 보내므로
 * 여기서 한 번 더 보내지 않는다(`signingIn`).
 *
 * **영화 티켓 디자인** (받은 시안, 2026-09-14, S15P21E106-319). 모든 요소가 모서리가 파인
 * 티켓 한 장 위에 선다. 절취선 아래 꼬리표에는 장식 바코드가 찍힌다. 티켓 테두리 · 절취선 ·
 * `로그인` 버튼은 브랜드 빨강이고, 소셜 버튼은 각 사 로고를 쓴다.
 */
import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import SocialButton from '../../../components/SocialButton';
import SocialNotice from '../../../components/SocialNotice';
import type { SocialProvider } from '../../../components/SocialButton';
import TextField from '../../../components/TextField';
import Wordmark from '../../../components/Wordmark';
import { ApiError } from '../../../api/apiError';
import { failureDescription } from '../../../components/StatusView/failure';
import { useAuth } from '../../../contexts/authContext';
import { path } from '../../../routes/paths';
import { isReturnPath } from '../../../routes/returnPath';
import type { SessionExpiryState } from '../../../routes/sessionExpiry';
import { isOAuthConfigured, startOAuth } from '../../../utils/oauth';
import styles from './LoginPage.module.css';
import Ticket, { Barcode } from './Ticket';

/** 와이어프레임 0.6의 나열 순서다 */
const PROVIDERS: { provider: SocialProvider; label: string }[] = [
  { provider: 'google', label: '구글 로그인' },
  { provider: 'kakao', label: '카카오 로그인' },
];

/** 서버 문구를 그대로 쓰지 않고 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 올바르지 않습니다.',
  RATE_LIMITED: '로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.',
};

type Failure = { code: string; message: string };

export default function LoginPage() {
  const navigate = useNavigate();
  const { status, user, signIn } = useAuth();

  /*
   * 0.6은 두 경로로 열린다. 보호 라우트가 보낸 경우 가려던 주소가 `state.from`에 있고
   * (`RequireAuth`, 업무 -610), 재발급이 거절돼 끌려온 경우 `state.sessionExpired`가 선다
   * (`routes/sessionExpiry.ts`, 업무 -596). 둘은 같은 state에 실리므로 한번에 읽는다.
   *
   * 로그인이 끝나면 `from`으로 돌아간다. 온보딩을 안 마친 계정은 먼저 1.1로 간다 — 그
   * 화면들이 온보딩 완료를 전제하기 때문이고, 그 뒤의 경로는 3.1 필름이다.
   *
   * 끌려온 것을 스스로 온 로그인과 구분해 알려 주지 않으면, 사용자는 멀쩡히 쓰던 중에 왜
   * 로그인 화면인지 알 수 없다. 서버 장애(5xx)는 세션을 끝내지 않으므로 여기로 오지 않는다(업무 -373).
   */
  const { state } = useLocation() as {
    state: ({ from?: unknown } & SessionExpiryState) | null;
  };
  const returnTo = isReturnPath(state?.from) ? state.from : null;
  const sessionExpired = Boolean(state?.sessionExpired);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  /** 이 화면에서 로그인을 시작했는지. 그때의 이동은 `submit`이 맡는다 */
  const [signingIn, setSigningIn] = useState(false);

  const submit = async () => {
    setPending(true);
    setSigningIn(true);
    setFailure(null);

    try {
      const user = await signIn(email, password);

      if (!user.onboardingCompleted) {
        void navigate(path.onboardingTaste(), { replace: true });
      } else {
        void navigate(returnTo ?? path.film(), { replace: true });
      }
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';

      /*
       * 화면이 정한 코드 밖의 실패는 다른 화면과 같은 기준으로 원인을 말한다(업무 -374).
       * 서버에 닿지 못한 것과 서버가 처리하지 못한 것을 가르고, 그 밖의 거절은 서버
       * 문구를 그대로 쓴다.
       */
      setFailure({ code, message: MESSAGE[code] ?? failureDescription(error) });
      setSigningIn(false);
    } finally {
      setPending(false);
    }
  };

  if (status === 'authenticated' && user && !signingIn) {
    return (
      <Navigate
        to={user.onboardingCompleted ? (returnTo ?? path.film()) : path.onboardingTaste()}
        replace
      />
    );
  }

  return (
    <div className={styles.page}>
      <Ticket stub={<Barcode />}>
        <h1 className={styles.logo}>
          <Wordmark size={40} />
        </h1>

        {sessionExpired ? (
          <p className={styles.notice} role="status">
            로그인 유효 시간이 지나 로그아웃됐어요. 다시 로그인해 주세요.
          </p>
        ) : null}

        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <TextField
            label="아이디 (이메일)"
            type="email"
            placeholder="아이디 (이메일)"
            autoComplete="username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />

          <TextField
            label="비밀번호"
            type="password"
            placeholder="비밀번호"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />

          {failure ? (
            <p className={styles.failure} role="alert">
              {failure.message}
            </p>
          ) : null}

          <div className={styles.submit}>
            <Button type="submit" disabled={pending}>
              {pending ? '로그인 중…' : '로그인'}
            </Button>
          </div>
        </form>

        <nav className={styles.links} aria-label="계정 도움말">
          <Link className={styles.link} to={path.accountFind()}>
            비밀번호 찾기
          </Link>
          <span className={styles.separator} aria-hidden="true">
            |
          </span>
          <Link className={styles.link} to={path.signupMethod()}>
            회원가입
          </Link>
        </nav>

        <div className={styles.divider}>
          <span className={styles.dividerLabel}>소셜 로그인</span>
        </div>

        <div className={styles.providers}>
          {PROVIDERS.map(({ provider, label }) => (
            <SocialButton
              key={provider}
              provider={provider}
              label={label}
              appearance="brand"
              disabled={!isOAuthConfigured(provider)}
              onClick={() => startOAuth(provider, 'login', returnTo ?? undefined)}
            />
          ))}
        </div>

        <SocialNotice action="로그인" />
      </Ticket>
    </div>
  );
}
