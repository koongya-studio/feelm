/*
 * 0.5 회원가입 완료
 *
 * 이동표: 0.5 → 1.1 `시작하기`
 *
 * 문구는 와이어프레임 0.5 그대로다. 가입이 끝났다는 것과 다음 걸음이 초기 취향
 * 설정이라는 것, 두 가지만 말하는 화면이라 다른 요소를 두지 않았다.
 */
import { useNavigate } from 'react-router';
import Button from '../../../components/Button';
import { path } from '../../../routes/paths';
import styles from './SignupCompletePage.module.css';

export default function SignupCompletePage() {
  const navigate = useNavigate();

  return (
    <div className={styles.page}>
      <div className={styles.stage}>
        <h1 className={styles.title}>회원가입 완료!</h1>
        <p className={styles.lead}>첫 취향 필름을 만들어볼까요?</p>
      </div>

      <div className={styles.actions}>
        <Button onClick={() => void navigate(path.onboardingTaste())}>시작하기</Button>
      </div>
    </div>
  );
}
