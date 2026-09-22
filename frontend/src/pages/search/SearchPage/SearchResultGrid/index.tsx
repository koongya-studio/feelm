/*
 * 4.2 검색 결과 격자 · 검색 중 스켈레톤 (업무 S15P21E106-612)
 *
 * 412px 이하에서 한 칸은 **포스터와 제목뿐**이다. 연도 · 장르 · 평점 · OTT는 넣지 않는다 —
 * 한 화면에서 가능한 한 많은 결과를 훑게 하려고 3열로 촘촘히 두었고, 그 정보는 2.4 상세가
 * 준다. 칸 전체(포스터 + 제목)가 상세로 가는 링크다.
 *
 * 포스터는 모두 2:3이다. 이미지가 없으면 `Poster`의 자리표시자가 같은 규격으로 선다.
 *
 * 스켈레톤은 실제 칸과 같은 비율 · 같은 격자로 3열 × 3행을 그린다. 결과가 오면 같은
 * 자리에 포스터가 들어서므로 화면이 튀지 않는다.
 *
 * ─────────────────────────── 데스크톱 시안 (업무 -784)
 *
 * 넓은 화면(`variant="desktop"`)에서는 2026-09-21 PC 시안대로 **칸이 흰 카드가 되고 제목
 * 아래에 개봉연도 · 장르 · 평점 · OTT 배지가 선다.** 값은 모두 검색 응답에 이미 있는
 * 것들이다(API-028) — 좁은 화면에서 감췄던 이유는 자리가 없어서였다.
 *
 * **`onPick`을 주면 칸이 링크가 아니라 고르는 단추가 된다.** 1280px 이상에서는 고른 영화를
 * 우측 보조 패널이 말하고, 상세로 가는 것은 그 패널의 `영화 상세 보기`다(시안). 패널 자리가
 * 없는 폭에서는 `onPick` 없이 쓰므로 지금까지처럼 칸이 곧 상세 링크다.
 */
import { Link } from 'react-router';
import OttBadge from '../../../../components/OttBadge';
import Poster from '../../../../components/Poster';
import PosterCue from '../../../../components/PosterCue';
import VisuallyHidden from '../../../../components/VisuallyHidden';
import { path } from '../../../../routes/paths';
import type { ReactNode } from 'react';
import type { Ott } from '../../../../types/ott';
import type { MovieSearchItem } from '../../../../types/search';
import styles from './SearchResultGrid.module.css';

/** 포스터 비율 2:3. 격자는 화면 폭을 따라가므로 비율만 고정한다 */
const POSTER_RATIO = { width: 2, height: 3 };
/** 포스터 모서리. 요구는 10~12px이다 */
const POSTER_RADIUS = 12;
/** 스켈레톤 칸 수. 한 화면에 보이는 3열 × 3행이다 */
const SKELETON_CELLS = 9;
/** 데스크톱 카드의 OTT 배지 한 변(px). 시안의 크기다 */
const OTT_SIZE = 22;

type Props = {
  items: MovieSearchItem[];
  /** 칸을 눌러 상세로 갈 때. 검색어를 최근 검색어로 남기는 자리다 */
  onSelect?: (movie: MovieSearchItem) => void;
  /**
   * `desktop` 흰 카드에 연도 · 장르 · 평점 · OTT까지 (업무 -784).
   * 기본은 412px 이하의 포스터 + 제목이다
   */
  variant?: 'compact' | 'desktop';
  /** OTT 이름을 찾을 기준정보(API-025). `desktop`에서만 쓴다 */
  otts?: Ott[];
  /** 고른 영화. 시안이 그 칸에 빨간 테두리를 둔다 */
  selectedId?: number | null;
  /** 주면 칸이 상세 링크가 아니라 고르는 단추가 된다(머리말) */
  onPick?: (movie: MovieSearchItem) => void;
};

export default function SearchResultGrid({
  items,
  onSelect,
  variant = 'compact',
  otts = [],
  selectedId = null,
  onPick,
}: Props) {
  const desktop = variant === 'desktop';

  /** 카드 안쪽. 링크든 단추든 같은 것을 담는다 */
  const content = (movie: MovieSearchItem): ReactNode => (
    <>
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

      {desktop ? (
        <>
          <span className={styles.meta}>
            {[movie.releaseYear ? String(movie.releaseYear) : null, ...movie.genres]
              .filter(Boolean)
              .join(' · ')}
          </span>

          {/*
           * 평가가 하나도 없으면 계약이 `averageRating: 0`을 준다. 그때는 별을 그리지
           * 않는다 — `★ 0.0`은 모두가 싫어한 영화라는 뜻으로 읽힌다(업무 -783 · -771).
           * 줄은 비워 두어 카드 높이가 갈리지 않게 한다
           */}
          <span className={styles.rating}>
            {movie.averageRating > 0 ? (
              <>
                <span className={styles.star} aria-hidden="true">
                  ★
                </span>
                {movie.averageRating.toFixed(1)}
              </>
            ) : null}
          </span>

          <span className={styles.otts}>
            {movie.ottProviders.map((code) => (
              <OttBadge
                key={code}
                code={code}
                name={otts.find((ott) => ott.code === code)?.name ?? code}
                size={OTT_SIZE}
              />
            ))}
          </span>
        </>
      ) : null}
    </>
  );

  return (
    <ul className={styles.grid} data-variant={variant}>
      {items.map((movie) => (
        <li key={movie.movieId}>
          {onPick ? (
            <button
              type="button"
              className={styles.cell}
              data-selected={movie.movieId === selectedId}
              aria-pressed={movie.movieId === selectedId}
              onClick={() => onPick(movie)}
            >
              {content(movie)}
            </button>
          ) : (
            <Link
              to={path.movieDetail(movie.movieId)}
              className={styles.cell}
              onClick={() => onSelect?.(movie)}
            >
              {content(movie)}
            </Link>
          )}
        </li>
      ))}
    </ul>
  );
}

/** 검색 중. 보조기술에는 한 줄로 알리고 격자는 장식으로 둔다 */
export function SearchResultSkeleton({
  cells = SKELETON_CELLS,
  variant = 'compact',
}: {
  cells?: number;
  variant?: 'compact' | 'desktop';
}) {
  return (
    <>
      <VisuallyHidden>
        <p role="status">검색 중</p>
      </VisuallyHidden>

      <ul className={styles.grid} data-variant={variant} aria-hidden="true">
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
