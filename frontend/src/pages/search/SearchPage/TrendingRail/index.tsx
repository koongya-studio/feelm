/*
 * 4.1 검색 홈의 급상승 영화 한 줄 (업무 S15P21E106-798)
 *
 * API-064가 준 순위대로 옆으로 넘기는 포스터 줄이다. 칸은 포스터 · 순위 · 제목 · 순위
 * 변동이고, 칸 전체가 2.4 상세로 가는 링크다. 격자(`SearchResultGrid`)를 쓰지 않은 것은
 * 순위가 있는 목록은 왼쪽에서 오른쪽으로 읽혀야 1위가 먼저 보이기 때문이다 — 3열 격자는
 * 열 방향이 먼저 읽혀 순서가 흐려진다.
 *
 * **순위 변동은 있을 때만 그린다.** 계약은 직전 게시 대비 상승 폭을 `rankDelta`로 주고
 * 신규 진입은 `null`이다. 초기 고정 목록(`source=DEFAULT`)에서는 모두 `null`이라 그때는
 * 변동 줄이 통째로 비고, 그것을 `NEW`라고 부르지 않는다 — 집계된 적이 없는 목록에 신규
 * 진입은 없다. 그래서 `NEW`는 집계 결과(`source=VIEW`)에서만 쓴다.
 *
 * 순위 배지는 무채색이다. UI에 유채색을 쓰지 않는 규칙(design-concept.md)이고, 포스터가
 * 시각의 중심이라 배지는 어두운 면에 흰 숫자로만 앉는다.
 */
import { Link } from 'react-router';
import Poster from '../../../../components/Poster';
import PosterCue from '../../../../components/PosterCue';
import { path } from '../../../../routes/paths';
import type { TrendingMovieItem, TrendingSource } from '../../../../types/search';
import styles from './TrendingRail.module.css';

/** 포스터 너비 · 높이(px). 2.4 상세의 유사 영화 줄과 같은 96 × 144다 */
const POSTER_WIDTH = 96;
const POSTER_HEIGHT = 144;
/** 포스터 모서리. 요구는 10~12px이다 */
const POSTER_RADIUS = 10;

type Props = {
  items: TrendingMovieItem[];
  /** `DEFAULT`면 순위 변동을 그리지 않는다(머리말) */
  source: TrendingSource;
};

/** 순위 변동 한 줄. 없으면 `null`이라 줄 자체를 비운다 */
function deltaLabel(item: TrendingMovieItem, source: TrendingSource): string | null {
  if (source !== 'VIEW') return null;
  if (item.rankDelta === null) return 'NEW';
  if (item.rankDelta > 0) return `↑${item.rankDelta}`;
  if (item.rankDelta < 0) return `↓${-item.rankDelta}`;

  return null;
}

export default function TrendingRail({ items, source }: Props) {
  return (
    <ul className={styles.rail}>
      {items.map((item) => {
        const delta = deltaLabel(item, source);

        return (
          <li key={item.movie.movieId}>
            <Link
              to={path.movieDetail(item.movie.movieId)}
              className={styles.cell}
              aria-label={`${item.rank}위 ${item.movie.title}${delta ? ` · ${delta}` : ''}`}
            >
              <span className={styles.posterWrap}>
                <PosterCue>
                  <Poster
                    width={POSTER_WIDTH}
                    height={POSTER_HEIGHT}
                    radius={POSTER_RADIUS}
                    src={item.movie.posterUrl ?? undefined}
                    title={item.movie.title}
                    seed={item.movie.movieId}
                  />
                </PosterCue>
                <span className={styles.rank} aria-hidden="true">
                  {item.rank}
                </span>
              </span>

              <span className={styles.title} aria-hidden="true">
                {item.movie.title}
              </span>

              <span className={styles.delta} aria-hidden="true" data-new={delta === 'NEW'}>
                {delta}
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
