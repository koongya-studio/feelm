/*
 * 0.1 시작 화면
 *
 * 이동은 이동표를 따른다.
 * 0.1 → 0.2 `시작하기` · 0.1 ⇢ 0.6 `로그인`
 *
 * **로고 하나만 정중앙에 선다** (팀 결정 2026-09-14, S15P21E106-319). 와이어프레임 0.1의
 * 기울인 포스터 격자와 소개 문구는 뺐다. 로그인 전이라 실제 포스터를 고를 근거가 없어 격자가
 * 회색 빈 카드로만 채워졌기 때문이다. 배경은 앱 공통 벽 그대로이고, 주 동작 `시작하기`와
 * 기존 사용자를 위한 `로그인` 링크만 화면 아래에 붙는다.
 */
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import Wordmark from '../../../components/Wordmark';
import { path } from '../../../routes/paths';
import styles from './StartPage.module.css';

export default function StartPage() {
  const navigate = useNavigate();

  return (
    <div className={styles.page}>
      <h1 className={styles.logo}>
        <Wordmark size={64} />
      </h1>

      <div className={styles.actions}>
        <Button onClick={() => void navigate(path.signupMethod())}>시작하기</Button>

        <p className={styles.login}>
          이미 계정이 있나요?{' '}
          <Link className={styles.link} to={path.login()}>
            로그인
          </Link>
        </p>
      </div>
    </div>
  );
}
