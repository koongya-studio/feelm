/*
 * 영화 한 줄 — 포스터 · 제목 · 보조 정보 · 끝 동작.
 *
 * 7.5 추천 기록 · 7.6 평가한 영화 · 7.7 평가하지 않은 영화가 같은 줄을 쓴다(업무 -306).
 * 와이어프레임 세 화면 모두 `56×78 포스터 + 제목 + 날짜 줄 + 오른쪽 끝 요소`로 그렸다.
 *
 * **줄 전체를 누르는 영역과 끝 동작을 형제로 둔다.** 끝 동작이 버튼인데 줄이 링크면
 * 링크 안에 버튼이 들어가 잘못된 마크업이 되고, 보조기술이 둘을 하나로 읽는다.
 *
 * 포스터는 어두운 콘텐츠다. 줄 자체는 벽 위의 선으로만 나눈다 — 카드로 감싸면 목록이
 * 상자 더미가 된다(`design-concept.md` 1장 `밝은 벽에 어두운 것들을 건다`).
 */
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { ChevronRightIcon } from '../Icon';
import Poster from '../Poster';
import styles from './MovieRow.module.css';

const POSTER_WIDTH = 56;
const POSTER_HEIGHT = 78;

type Props = {
  movie: { movieId: number; title: string; posterUrl: string | null };
  /** 제목 아래 줄들 */
  children?: ReactNode;
  /** 줄을 누르면 가는 곳. 없으면 줄은 누를 수 없다 */
  to?: string;
  /** 오른쪽 끝 요소. 버튼이면 줄과 별개로 눌린다 */
  trailing?: ReactNode;
};

export default function MovieRow({ movie, children, to, trailing }: Props) {
  const body = (
    <>
      <Poster
        width={POSTER_WIDTH}
        height={POSTER_HEIGHT}
        radius={8}
        src={movie.posterUrl ?? undefined}
        title={movie.title}
        seed={movie.movieId}
      />

      <span className={styles.text}>
        <span className={styles.title}>{movie.title}</span>
        {children}
      </span>

      {/*
       * 누를 수 있는 줄의 표시. 링크 안에 두어야 화살표를 눌러도 이동한다.
       * 끝 동작이 있어도 둔다 — 7.6에서 한줄평이 있는 줄만 화살표가 사라지면 그 줄만
       * 누를 수 없어 보인다.
       */}
      {to ? (
        <span className={styles.chevron} aria-hidden="true">
          <ChevronRightIcon />
        </span>
      ) : null}
    </>
  );

  return (
    <div className={styles.row}>
      {to ? (
        <Link to={to} className={styles.main}>
          {body}
        </Link>
      ) : (
        <div className={styles.main}>{body}</div>
      )}

      {trailing ? <div className={styles.trailing}>{trailing}</div> : null}
    </div>
  );
}
