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
 * **가입이 실제로 끝났는지는 세션 저장소의 표시로 판단한다**(`utils/signupCompletion`,
 * 업무 -610). 라우터 state만 보던 때는 주소를 직접 쳐서 들어와도 소셜 가입 성공 화면이
 * 나왔다. 표시가 없으면 0.2 가입 방법 선택으로 보낸다. 이메일로 왔는지도 그 표시가 말한다.
 *
 * **봉인된 편지 → 열린 봉투 → 초대장이 올라오는 모션** (팀 결정 2026-09-14, S15P21E106-319).
 * - 첫 장면은 받은 봉인된 빨간 편지 사진이다. 봉인이 한 번 눌린 뒤 사라지며 열린 봉투로
 *   바뀐다. 두 그림은 구도가 달라 조각을 이어 붙일 수 없으므로 겹쳐서 넘긴다
 * - 열린 봉투는 받은 아트워크(`Welcome!` 초대장이 꽂힌 누끼 PNG)를 WebP로 옮긴 것이다.
 *   초대장 문구는 그림 안에 있다
 * - 그림 한 장을 같은 자리에 네 번 깔고 봉투 뒷면 · 초대장 · 앞 주머니 · 봉인으로 오린다.
 *   끝 상태에서는 원래 그림과 똑같고, 초대장 조각만 봉투 안에서 올라온다. 초대장이 비운
 *   자리(봉투 안쪽)만 코드로 칠한다
 * - 봉투와 봉인의 빨강은 UI가 아니라 아트워크 안의 색이다(`design-concept.md` 3장)
 * - CSS 애니메이션만 쓴다. 기본 스타일이 끝 상태라 `prefers-reduced-motion`이면 곧바로
 *   끝 상태로 그린다
 *
 * 문구가 그림 안에 있으므로 제목(h1)은 화면에 보이지 않게 두어 보조기술이 읽게 한다.
 */
import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Navigate, useNavigate } from 'react-router';
import sealedArt from '../../../assets/signup/feelm-sealed-envelope.webp';
import envelopeArt from '../../../assets/signup/feelm-welcome-envelope.webp';
import Button from '../../../components/Button';
import VisuallyHidden from '../../../components/VisuallyHidden';
import { resendVerificationMail } from '../../../api/auth';
import { path } from '../../../routes/paths';
import { clearSignupCompletion, readSignupCompletion } from '../../../utils/signupCompletion';
import styles from './SignupCompletePage.module.css';

export default function SignupCompletePage() {
  const navigate = useNavigate();
  const completion = readSignupCompletion();
  const email = completion?.kind === 'email' ? completion.email : undefined;

  const [resent, setResent] = useState(false);
  const [pending, setPending] = useState(false);

  // 가입을 마치지 않은 사람에게 성공 화면을 보여 주지 않는다
  if (!completion) {
    return <Navigate to={path.signupMethod()} replace />;
  }

  // 이 화면을 떠나면 표시를 지운다. 뒤로 가기로 다시 성공 화면을 보는 일이 없다
  const leave = (to: string) => {
    clearSignupCompletion();
    void navigate(to, { replace: true });
  };

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
        <VisuallyHidden>
          <h1>회원가입이 완료되었습니다</h1>
        </VisuallyHidden>

        <div
          className={styles.scene}
          style={
            {
              '--envelope-image': `url(${envelopeArt})`,
              '--sealed-image': `url(${sealedArt})`,
            } as CSSProperties
          }
          aria-hidden="true"
        >
          <div className={styles.envelope}>
            <div className={styles.inside} />
            <div className={`${styles.art} ${styles.back}`} />
            <div className={styles.slot}>
              <div className={`${styles.art} ${styles.card}`} />
            </div>
            <div className={`${styles.art} ${styles.pocket}`} />
            <div className={`${styles.art} ${styles.seal}`} />
          </div>

          {/* 첫 장면. 봉인된 빨간 편지가 먼저 나오고, 봉인이 눌린 뒤 열린 봉투로 바뀐다 */}
          <div className={styles.sealed}>
            <div className={styles.sealedStamp} />
          </div>
        </div>

        {email ? (
          <div className={styles.after}>
            <p className={styles.lead}>
              <span className={styles.email}>{email}</span>으로 인증 메일을 보냈어요.
              <br />
              메일의 링크를 눌러야 로그인할 수 있어요.
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
          </div>
        ) : (
          <p className={`${styles.lead} ${styles.after}`}>첫 취향 필름을 만들어볼까요?</p>
        )}
      </div>

      <div className={`${styles.actions} ${styles.after}`}>
        {email ? (
          <Button onClick={() => leave(path.login())}>로그인하러 가기</Button>
        ) : (
          <Button onClick={() => leave(path.onboardingTaste())}>시작하기</Button>
        )}
      </div>
    </div>
  );
}
