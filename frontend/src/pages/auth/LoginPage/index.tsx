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
 * 실패는 세 가지를 구분한다. 자격 증명이 틀린 것(`INVALID_CREDENTIALS`)은 다시 입력하면
 * 되고, 미인증 계정(`EMAIL_NOT_VERIFIED`)은 입력을 고쳐도 소용이 없어 인증 메일 재발송
 * (API-003)이 필요하며, 시도 제한(`RATE_LIMITED`)은 기다리는 것 말고 할 일이 없다.
 * 셋 다 같은 문구를 보여 주면 사용자가 다음에 무엇을 해야 할지 알 수 없다.
 *
 * 와이어프레임의 `아이디 찾기` · `비밀번호 찾기`는 둘 다 0.7로 간다. 0.7이 두 갈래를
 * 탭으로 나눠 갖고 있으므로 어느 탭으로 열지를 함께 넘긴다.
 *
 * `[?]` 소셜 로그인(API-007~009)은 이번 연동 범위가 아니다. 목업으로 로그인한 척하면
 * 세션 없이 홈으로 들어가므로 버튼을 잠가 두었다.
 *
 * **영화 티켓 디자인** (받은 시안, 2026-09-14, S15P21E106-319). 모든 요소가 모서리가 파인
 * 티켓 한 장 위에 선다. 절취선 아래 꼬리표에는 장식 바코드가 찍힌다. 티켓 테두리 · 절취선 ·
 * `로그인` 버튼은 브랜드 빨강이고, 소셜 버튼은 각 사 로고를 쓴다.
 */
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import SocialButton from '../../../components/SocialButton';
import type { SocialProvider } from '../../../components/SocialButton';
import TextField from '../../../components/TextField';
import Wordmark from '../../../components/Wordmark';
import { resendVerificationMail } from '../../../api/auth';
import { ApiError } from '../../../api/apiError';
import { useAuth } from '../../../contexts/authContext';
import { path } from '../../../routes/paths';
import styles from './LoginPage.module.css';
import Ticket, { Barcode } from './Ticket';

/** 와이어프레임 0.6의 나열 순서다 */
const PROVIDERS: { provider: SocialProvider; label: string }[] = [
  { provider: 'google', label: '구글 로그인' },
  { provider: 'naver', label: '네이버 로그인' },
  { provider: 'kakao', label: '카카오 로그인' },
];

/** 서버 문구를 그대로 쓰지 않고 화면이 정하는 경우만 둔다 */
const MESSAGE: Record<string, string> = {
  INVALID_CREDENTIALS: '이메일 또는 비밀번호가 올바르지 않습니다.',
  EMAIL_NOT_VERIFIED: '이메일 인증을 마쳐야 로그인할 수 있습니다.',
  RATE_LIMITED: '로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.',
};

type Failure = { code: string; message: string };

export default function LoginPage() {
  const navigate = useNavigate();
  const { signIn } = useAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [resent, setResent] = useState(false);

  const submit = async () => {
    setPending(true);
    setFailure(null);
    setResent(false);

    try {
      const user = await signIn(email, password);

      void navigate(user.onboardingCompleted ? path.home() : path.onboardingTaste());
    } catch (error) {
      const code = error instanceof ApiError ? error.code : '';
      const fallback =
        error instanceof ApiError
          ? error.message
          : '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.';

      setFailure({ code, message: MESSAGE[code] ?? fallback });
    } finally {
      setPending(false);
    }
  };

  /* 재발송은 계정 존재 여부를 알려 주지 않으므로(API-003) 성공 여부를 나누지 않는다 */
  const resend = async () => {
    try {
      await resendVerificationMail(email);
    } finally {
      setResent(true);
    }
  };

  return (
    <div className={styles.page}>
      <Ticket stub={<Barcode />}>
        <h1 className={styles.logo}>
          <Wordmark size={40} />
        </h1>

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

          {failure?.code === 'EMAIL_NOT_VERIFIED' && !resent ? (
            <Button type="button" variant="text" onClick={() => void resend()}>
              인증 메일 다시 보내기
            </Button>
          ) : null}

          {resent ? (
            <p className={styles.notice} role="status">
              인증 메일을 다시 보냈습니다. 메일함을 확인해 주세요.
            </p>
          ) : null}

          <div className={styles.submit}>
            <Button type="submit" disabled={pending}>
              {pending ? '로그인 중…' : '로그인'}
            </Button>
          </div>
        </form>

        <nav className={styles.links} aria-label="계정 도움말">
          <Link className={styles.link} to={path.accountFind()} state={{ tab: 'id' }}>
            아이디 찾기
          </Link>
          <span className={styles.separator} aria-hidden="true">
            |
          </span>
          <Link className={styles.link} to={path.accountFind()} state={{ tab: 'password' }}>
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
              disabled
            />
          ))}
        </div>

        <p className={styles.notice}>소셜 로그인은 준비 중입니다.</p>
      </Ticket>
    </div>
  );
}
