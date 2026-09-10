/*
 * 0.6 로그인
 *
 * 이동표
 * 0.6 ⇢ 0.7 아이디 찾기 · 비밀번호 찾기
 * 0.6 ⇢ 1.1 신규 사용자가 처음 로그인한 경우
 * 0.6 → 2.1 취향 설정을 완료한 기존 사용자 로그인
 *
 * 신규와 기존을 가르는 것은 초기 취향 설정을 마쳤는지다. 목업 프로필의
 * `onboardingCompleted`로 판단한다(API-014가 내려주는 값). 실제 로그인 응답이 아니라
 * 화면 이동을 보여주기 위한 목업이다.
 *
 * 와이어프레임의 `아이디 찾기` · `비밀번호 찾기`는 둘 다 0.7로 간다. 0.7이 두 갈래를
 * 탭으로 나눠 갖고 있으므로 어느 탭으로 열지를 함께 넘긴다.
 */
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import SocialButton from '../../../components/SocialButton';
import type { SocialProvider } from '../../../components/SocialButton';
import TextField from '../../../components/TextField';
import Wordmark from '../../../components/Wordmark';
import { mockMyProfile } from '../../../mocks/user';
import { path } from '../../../routes/paths';
import styles from './LoginPage.module.css';

/** 와이어프레임 0.6의 나열 순서다 */
const PROVIDERS: { provider: SocialProvider; label: string }[] = [
  { provider: 'google', label: '구글 로그인' },
  { provider: 'naver', label: '네이버 로그인' },
  { provider: 'kakao', label: '카카오 로그인' },
];

export default function LoginPage() {
  const navigate = useNavigate();

  const signIn = () => {
    const next = mockMyProfile.onboardingCompleted ? path.home() : path.onboardingTaste();
    void navigate(next);
  };

  return (
    <div className={styles.page}>
      <h1 className={styles.logo}>
        <Wordmark size={40} />
      </h1>

      <form
        className={styles.form}
        onSubmit={(event) => {
          event.preventDefault();
          signIn();
        }}
      >
        <TextField
          label="아이디 (이메일)"
          type="email"
          placeholder="아이디 (이메일)"
          autoComplete="username"
        />

        <TextField
          label="비밀번호"
          type="password"
          placeholder="비밀번호"
          autoComplete="current-password"
        />

        <Button type="submit">로그인</Button>
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
          <SocialButton key={provider} provider={provider} label={label} onClick={signIn} />
        ))}
      </div>
    </div>
  );
}
