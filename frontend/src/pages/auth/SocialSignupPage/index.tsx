/*
 * 0.3 소셜 계정 회원가입
 *
 * 이동표
 * 0.4 ⇢ 0.3 소셜 계정으로 가입 선택
 * 0.3 → 0.5 소셜 계정 가입 성공
 *
 * 0.2와 달리 문구가 `소셜 계정으로 가입`이고 제공자 이름만 한 단어로 나온다. 이메일
 * 입력을 하다 소셜로 돌아선 사용자가 보는 화면이라 권유 문구가 없다.
 */
import { useNavigate } from 'react-router';
import ScreenHeader from '../../../components/ScreenHeader';
import SocialButton from '../../../components/SocialButton';
import type { SocialProvider } from '../../../components/SocialButton';
import { path } from '../../../routes/paths';
import styles from './SocialSignupPage.module.css';

/** 와이어프레임 0.3은 제공자 이름만 쓴다 */
const PROVIDERS: { provider: SocialProvider; label: string }[] = [
  { provider: 'google', label: '구글' },
  { provider: 'naver', label: '네이버' },
  { provider: 'kakao', label: '카카오' },
];

export default function SocialSignupPage() {
  const navigate = useNavigate();

  return (
    <>
      <ScreenHeader title="소셜 계정으로 가입" showBack />

      <div className={styles.page}>
        {PROVIDERS.map(({ provider, label }) => (
          <SocialButton
            key={provider}
            provider={provider}
            label={label}
            onClick={() => void navigate(path.signupComplete())}
          />
        ))}
      </div>
    </>
  );
}
