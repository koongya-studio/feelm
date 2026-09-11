/*
 * 0.5 회원가입 완료
 *
 * 이동표
 * 0.2 → 0.5 소셜 가입 완료
 * 0.4 → 0.5 이메일 회원가입 완료
 * 0.5 → 1.1 시작하기
 *
 * 두 경로가 같은 화면으로 들어오는데 계정 상태가 다르다. 소셜 가입은 곧바로 쓸 수 있지만
 * 이메일 가입은 `PENDING_VERIFICATION`이라 인증을 마쳐야 로그인할 수 있다(API-001).
 * 그래서 이메일로 온 경우에는 `시작하기` 대신 인증 안내와 재발송을 보여준다. 그대로
 * 온보딩으로 보내면 세션이 없어 회원 API가 막힌다(screen-spec-auth.md 0.5, CR-19).
 *
 * 이메일로 왔는지는 0.4가 넘긴 `state.email`로 판단한다.
 */
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import { resendVerificationMail } from '../../../api/auth';
import { path } from '../../../routes/paths';
import styles from './SignupCompletePage.module.css';

type State = { email?: string } | null;

export default function SignupCompletePage() {
  const navigate = useNavigate();
  const { state } = useLocation() as { state: State };
  const email = state?.email;

  const [resent, setResent] = useState(false);
  const [pending, setPending] = useState(false);

  /* 재발송은 계정 존재 여부를 알려주지 않으므로(API-003) 성공과 실패를 나누지 않는다 */
  const resend = async () => {
    if (!email) {
      return;
    }

    setPending(true);

    try {
      await resendVerificationMail(email);
    } finally {
      setPending(false);
      setResent(true);
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        <h1 className={styles.title}>회원가입 완료!</h1>

        {email ? (
          <>
            <p className={styles.lead}>
              {email} 으로 인증 메일을 보냈어요. 메일의 링크를 눌러야 로그인할 수 있어요.
            </p>

            {resent ? (
              <p className={styles.notice} role="status">
                인증 메일을 다시 보냈습니다. 메일함을 확인해 주세요.
              </p>
            ) : (
              <Button variant="text" disabled={pending} onClick={() => void resend()}>
                메일이 오지 않았나요? 다시 보내기
              </Button>
            )}
          </>
        ) : (
          <p className={styles.lead}>첫 취향 필름을 만들어볼까요?</p>
        )}
      </div>

      <div className={styles.actions}>
        {email ? (
          <Button onClick={() => void navigate(path.login())}>로그인하러 가기</Button>
        ) : (
          <Button onClick={() => void navigate(path.onboardingTaste())}>시작하기</Button>
        )}
      </div>
    </div>
  );
}
