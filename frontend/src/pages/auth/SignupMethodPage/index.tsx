/*
 * 0.2 가입 방식 선택
 *
 * 이동표
 * 0.2 → 0.5 카카오·네이버·구글 가입 완료
 * 0.2 → 0.4 이메일로 가입하기
 * 0.2 ⇢ 0.6 이미 계정이 있는 사용자의 로그인
 *
 * 소셜 가입이 0.5로 바로 가는 것은 이동표가 정한 것이다. 0.3 소셜 계정 회원가입은
 * 0.4에서 `소셜 계정으로 가입`을 고를 때 들어가는 화면이라(0.4 ⇢ 0.3) 여기서는
 * 거치지 않는다.
 *
 * `[?]` 실제 OAuth 이동은 이번 범위가 아니다. 목업이라 제공자를 눌러도 곧바로 0.5로
 * 간다.
 */
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import SocialButton from '../../../components/SocialButton';
import type { SocialProvider } from '../../../components/SocialButton';
import Wordmark from '../../../components/Wordmark';
import { path } from '../../../routes/paths';
import styles from './SignupMethodPage.module.css';

/** 와이어프레임 0.2의 나열 순서다 */
const PROVIDERS: { provider: SocialProvider; label: string }[] = [
  { provider: 'kakao', label: '카카오로 시작하기' },
  { provider: 'naver', label: '네이버로 시작하기' },
  { provider: 'google', label: '구글로 시작하기' },
];

export default function SignupMethodPage() {
  const navigate = useNavigate();

  return (
    <div className={styles.page}>
      <h1 className={styles.logo}>
        <Wordmark size={40} />
      </h1>

      <p className={styles.lead}>
        3초 만에 가입하고
        <br />첫 취향 필름을 감아 보세요
      </p>

      <div className={styles.providers}>
        {PROVIDERS.map(({ provider, label }) => (
          <SocialButton
            key={provider}
            provider={provider}
            label={label}
            onClick={() => void navigate(path.signupComplete())}
          />
        ))}
      </div>

      <div className={styles.divider}>
        <span className={styles.dividerLabel}>또는</span>
      </div>

      <Button variant="secondary" onClick={() => void navigate(path.signupEmail())}>
        이메일로 가입하기
      </Button>

      <p className={styles.login}>
        이미 계정이 있나요?{' '}
        <Link className={styles.link} to={path.login()}>
          로그인
        </Link>
      </p>
    </div>
  );
}
