/*
 * 0.8 이메일 인증
 *
 * 이동표
 * 메일 링크 ⇢ 0.8
 * 0.8 → 0.6 인증 완료 또는 이미 인증된 계정
 *
 * 인증 메일의 링크로 열린다. 주소의 `token`으로 API-002를 호출해 계정을 활성화한다.
 * 토큰은 서버가 발급한 불투명 문자열이므로 해석하지 않고 그대로 넘긴다.
 *
 * 계약이 가르는 네 상태를 화면도 가른다(screen-spec-auth.md 0.8). 한 문구로 뭉뚱그리면
 * 사용자가 다음에 무엇을 할지 알 수 없다. 만료만 재발송이 필요하고 나머지는 아니다.
 *
 * 재발송(API-003)은 이메일을 받는데 이 화면은 토큰만 안다. 토큰은 불투명해서 이메일을
 * 꺼낼 수 없으므로, 만료일 때만 이메일을 물어 재발송한다.
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import Button from '../../../components/Button';
import TextField from '../../../components/TextField';
import { ApiError } from '../../../api/apiError';
import { resendVerificationMail, verifyEmail } from '../../../api/auth';
import { path } from '../../../routes/paths';
import styles from './VerifyEmailPage.module.css';

type Result =
  /** 확인하는 중. 링크를 누른 직후다 */
  | { kind: 'pending' }
  | { kind: 'success' }
  /** 토큰이 위조됐거나 형식이 틀렸다. 주소가 잘린 경우도 여기다 */
  | { kind: 'invalid' }
  | { kind: 'expired' }
  | { kind: 'alreadyVerified' }
  /** 계약에 없는 실패. 서버 문구를 그대로 보여준다 */
  | { kind: 'failed'; message: string };

const MESSAGE: Record<Exclude<Result['kind'], 'pending' | 'failed'>, string> = {
  success: '이메일 인증이 완료됐어요. 이제 로그인할 수 있어요.',
  invalid: '인증 링크가 올바르지 않아요. 메일의 링크를 다시 확인해 주세요.',
  expired: '인증 링크가 만료됐어요. 메일을 다시 받아 주세요.',
  alreadyVerified: '이미 인증을 마친 계정이에요. 바로 로그인할 수 있어요.',
};

function resultOf(error: unknown): Result {
  if (!(error instanceof ApiError)) {
    return { kind: 'failed', message: '인증을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.' };
  }

  switch (error.code) {
    case 'VERIFICATION_TOKEN_INVALID':
      return { kind: 'invalid' };
    case 'VERIFICATION_TOKEN_EXPIRED':
      return { kind: 'expired' };
    case 'ALREADY_VERIFIED':
      return { kind: 'alreadyVerified' };
    default:
      return { kind: 'failed', message: error.message };
  }
}

export default function VerifyEmailPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token');

  /*
   * 주소에 토큰이 없으면 부를 것도 없다. 효과 안에서 상태를 바꾸는 대신 처음부터 결과를
   * 정한다(react-hooks/set-state-in-effect).
   */
  const [result, setResult] = useState<Result>(token ? { kind: 'pending' } : { kind: 'invalid' });
  const [email, setEmail] = useState('');
  const [resent, setResent] = useState(false);

  /*
   * 토큰은 1회용이라 같은 토큰으로 두 번 부르면 두 번째는 실패한다. StrictMode가 효과를
   * 두 번 실행하므로 첫 호출만 보내도록 막는다.
   */
  const requested = useRef(false);

  useEffect(() => {
    if (requested.current) {
      return;
    }

    if (!token) {
      return;
    }

    requested.current = true;

    void verifyEmail(token)
      .then(() => setResult({ kind: 'success' }))
      .catch((error: unknown) => setResult(resultOf(error)));
  }, [token]);

  /* 재발송은 계정 존재 여부를 알려주지 않으므로(API-003) 성공과 실패를 나누지 않는다 */
  const resend = async () => {
    try {
      await resendVerificationMail(email);
    } finally {
      setResent(true);
    }
  };

  if (result.kind === 'pending') {
    // 데이터를 받기 전에는 아무것도 그리지 않는다(팀 결정 2026-09-08)
    return null;
  }

  const message = result.kind === 'failed' ? result.message : MESSAGE[result.kind];
  const canLogIn = result.kind === 'success' || result.kind === 'alreadyVerified';

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        <h1 className={styles.title}>이메일 인증</h1>
        <p className={styles.lead} role="status">
          {message}
        </p>

        {result.kind === 'expired' && !resent ? (
          <form
            className={styles.resend}
            onSubmit={(event) => {
              event.preventDefault();
              void resend();
            }}
          >
            <TextField
              label="가입한 이메일"
              type="email"
              placeholder="가입한 이메일"
              autoComplete="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <Button type="submit" variant="secondary">
              인증 메일 다시 받기
            </Button>
          </form>
        ) : null}

        {resent ? (
          <p className={styles.notice} role="status">
            인증 메일을 다시 보냈습니다. 메일함을 확인해 주세요.
          </p>
        ) : null}
      </div>

      <div className={styles.actions}>
        <Button onClick={() => void navigate(path.login())}>
          {canLogIn ? '로그인하러 가기' : '로그인으로 돌아가기'}
        </Button>
      </div>
    </div>
  );
}
