/*
 * 0.1 시작 화면
 *
 * 문구는 와이어프레임 0.1 그대로다. 이동은 이동표를 따른다.
 * 0.1 → 0.2 `시작하기` · 0.1 ⇢ 0.6 `로그인`
 *
 * 와이어프레임은 배경에 포스터 격자를 -8도로 기울여 깔고 위에서 아래로 흰색
 * 그라디언트를 덮는다. 포스터가 콘텐츠라는 규칙(`design-concept.md` 2.2)에 맞으므로
 * 구성을 그대로 두되, 실제 포스터 이미지 대신 `Poster`의 자리표시 표현을 쓴다.
 * 이 화면은 로그인 전이라 사용자별 포스터를 고를 근거가 없다.
 */
import { Link, useNavigate } from 'react-router';
import Button from '../../../components/Button';
import Poster from '../../../components/Poster';
import Wordmark from '../../../components/Wordmark';
import { path } from '../../../routes/paths';
import styles from './StartPage.module.css';

/** 배경 격자. 3열 × 3행이면 412px 폭에서 화면 위쪽이 가득 찬다(와이어프레임 0.1) */
const COLUMNS = [0, 1, 2];
const ROWS = [0, 1, 2];

export default function StartPage() {
  const navigate = useNavigate();

  return (
    <div className={styles.page}>
      <div className={styles.backdrop} aria-hidden="true">
        {COLUMNS.map((column) => (
          <div key={column} className={styles.column} data-column={column}>
            {ROWS.map((row) => (
              <Poster
                key={row}
                width={124}
                height={200}
                radius={10}
                title=""
                seed={column * 3 + row}
              />
            ))}
          </div>
        ))}
      </div>

      <div className={styles.veil} aria-hidden="true" />

      <div className={styles.content}>
        <h1 className={styles.logo}>
          <Wordmark size={52} />
        </h1>

        <p className={styles.tagline}>
          본 영화가 다음 영화를 고릅니다.
          <br />
          당신의 취향이 필름처럼 이어져요.
        </p>

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
