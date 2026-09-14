/*
 * 0.8-A 이메일 인증 완료
 *
 * 이동표
 * 0.8 → 0.8-A 인증 성공
 * 0.8-A → 0.6 로그인하러 가기
 *
 * 0.8은 확인만 하고 성공하면 이 화면으로 넘긴다. 성공을 0.8 안의 한 줄 문구로 두지 않고
 * 따로 뗀 것은 팀 결정이다(2026-09-14, S15P21E106-319).
 *
 * 0.8이 `replace`로 넘기므로 뒤로 가도 1회용 토큰을 다시 보내지 않는다. 이 화면 자체는
 * API를 부르지 않는다.
 */
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import { path } from '../../../routes/paths';
import styles from './VerifyEmailCompletePage.module.css';

export default function VerifyEmailCompletePage() {
  const navigate = useNavigate();

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        <h1 className={styles.title}>
          이메일 인증이
          <br />
          완료되었습니다
        </h1>
        <p className={styles.lead}>이제 로그인할 수 있어요.</p>
      </div>

      <div className={styles.actions}>
        <Button onClick={() => void navigate(path.login())}>로그인하러 가기</Button>
      </div>
    </div>
  );
}
