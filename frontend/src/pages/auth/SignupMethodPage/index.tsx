/*
 * 0.2 가입 방식 선택
 *
 * 이동표
 * 0.2 → 0.5 카카오·네이버·구글 가입 완료
 * 0.2 → 0.4 이메일로 가입하기
 * 0.2 ⇢ 0.6 이미 계정이 있는 사용자의 로그인
 *
 * 0.4에서 `소셜 계정으로 가입`을 고르면 이 화면으로 돌아온다(0.4 ⇢ 0.2). 한때 그 길 끝에
 * 소셜 버튼만 다시 늘어놓은 0.3이 따로 있었는데 이 화면과 하는 일이 같아 지웠다(CR-29,
 * 업무 -765).
 *
 * 이동표는 0.2 → 0.5로 곧장 가지만 실제로는 제공자 동의 화면과 콜백을 거치고, 연결된
 * 계정이 없으면 0.3-A에서 닉네임·생년월일·성별을 받는다. API-008이 요구하는 값이라
 * 받을 자리가 필요하다(CR-23, S15P21E106-499).
 *
 * 자격 증명이 설정되지 않은 제공자는 버튼을 잠근다. 눌러 봐야 백엔드가
 * `UNSUPPORTED_PROVIDER`로 답한다. 모두 잠겼으면 0.6과 같은 기준으로 준비 중이라고 알린다
 * (`SocialNotice`, 업무 -765).
 */
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import SocialButton from '../../../components/SocialButton';
import SocialNotice from '../../../components/SocialNotice';
import type { SocialProvider } from '../../../components/SocialButton';
import Wordmark from '../../../components/Wordmark';
import { path } from '../../../routes/paths';
import { isOAuthConfigured, startOAuth } from '../../../utils/oauth';
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
            disabled={!isOAuthConfigured(provider)}
            onClick={() => startOAuth(provider, 'signup')}
          />
        ))}
        <SocialNotice action="가입" />
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
