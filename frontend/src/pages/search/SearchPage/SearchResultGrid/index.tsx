/*
 * 4.2 검색 결과 격자 · 검색 중 스켈레톤 (업무 S15P21E106-612)
 *
 * 한 칸은 **포스터와 제목뿐**이다. 연도 · 장르 · 평점 · OTT는 넣지 않는다 — 한 화면에서
 * 가능한 한 많은 결과를 훑게 하려고 3열로 촘촘히 두었고, 그 정보는 2.4 상세가 준다.
 * 칸 전체(포스터 + 제목)가 상세로 가는 링크다.
 *
 * 포스터는 모두 2:3이다. 이미지가 없으면 `Poster`의 자리표시자가 같은 규격으로 선다.
 *
 * 스켈레톤은 실제 칸과 같은 비율 · 같은 격자로 3열 × 3행을 그린다. 결과가 오면 같은
 * 자리에 포스터가 들어서므로 화면이 튀지 않는다.
 */
import { Link } from 'react-router';
import Poster from '../../../../components/Poster';
import PosterCue from '../../../../components/PosterCue';
import VisuallyHidden from '../../../../components/VisuallyHidden';
import { path } from '../../../../routes/paths';
import type { MovieSearchItem } from '../../../../types/search';
import styles from './SearchResultGrid.module.css';

/** 포스터 비율 2:3. 격자는 화면 폭을 따라가므로 비율만 고정한다 */
const POSTER_RATIO = { width: 2, height: 3 };
/** 포스터 모서리. 요구는 10~12px이다 */
const POSTER_RADIUS = 12;
/** 스켈레톤 칸 수. 한 화면에 보이는 3열 × 3행이다 */
const SKELETON_CELLS = 9;

type Props = {
  items: MovieSearchItem[];
  /** 칸을 눌러 상세로 갈 때. 검색어를 최근 검색어로 남기는 자리다 */
  onSelect?: (movie: MovieSearchItem) => void;
};

export default function SearchResultGrid({ items, onSelect }: Props) {
  return (
    <ul className={styles.grid}>
      {items.map((movie) => (
        <li key={movie.movieId}>
          <Link
            to={path.movieDetail(movie.movieId)}
            className={styles.cell}
            onClick={() => onSelect?.(movie)}
          >
            <PosterCue>
              <Poster
                fluid
                width={POSTER_RATIO.width}
                height={POSTER_RATIO.height}
                radius={POSTER_RADIUS}
                src={movie.posterUrl ?? undefined}
                title={movie.title}
                seed={movie.movieId}
              />
            </PosterCue>
            <span className={styles.title}>{movie.title}</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** 검색 중. 보조기술에는 한 줄로 알리고 격자는 장식으로 둔다 */
export function SearchResultSkeleton({ cells = SKELETON_CELLS }: { cells?: number }) {
  return (
    <>
      <VisuallyHidden>
        <p role="status">검색 중</p>
      </VisuallyHidden>

      <ul className={styles.grid} aria-hidden="true">
        {Array.from({ length: cells }, (_, index) => (
          <li key={index} className={styles.cell}>
            <span className={styles.skeletonPoster} />
            <span className={styles.skeletonTitle} />
          </li>
        ))}
      </ul>
    </>
  );
}
