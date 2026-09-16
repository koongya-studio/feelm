/*
 * 4.2 검색 결과 한 줄.
 *
 * 포스터 · 제목 · `연도 · 장르 · ★ 평점` · OTT 배지다. 제공 OTT가 없으면 배지 자리에
 * `시청 가능한 OTT 없음`이 들어간다(와이어프레임 4.2의 마지막 행).
 *
 * `[!]` 와이어프레임의 평점은 `★ 7.3`처럼 10점 만점으로 적혀 있는데, 계약의
 * `averageRating`은 0.5~5.0이다(API-036 · FR-19). 계약을 기준으로 5점 만점으로 두었다
 * (`screen-spec.md` 2장 계약 우선 원칙). 목업 값은 와이어프레임 숫자를 그대로 옮겼으므로
 * 4.2 상단 네 편은 5점을 넘는 값으로 보인다 — 실제 데이터에서는 생기지 않는다.
 */
import OttBadge from '../../../../components/OttBadge';
import Poster from '../../../../components/Poster';
import type { MovieSearchItem } from '../../../../types/search';
import styles from './SearchResultRow.module.css';

/** 와이어프레임 4.2의 포스터 크기 */
const POSTER_WIDTH = 56;
const POSTER_HEIGHT = 80;

type Props = {
  movie: MovieSearchItem;
  /** OTT 코드 → 표시 이름. API-025 기준정보에서 만든다 */
  ottNames: Record<string, string>;
  onSelect: () => void;
};

export default function SearchResultRow({ movie, ottNames, onSelect }: Props) {
  const meta = [String(movie.releaseYear), movie.genres.join(' · ')].filter(Boolean).join(' · ');

  return (
    <button type="button" className={styles.row} onClick={onSelect}>
      <Poster
        width={POSTER_WIDTH}
        height={POSTER_HEIGHT}
        title={movie.title}
        seed={movie.movieId}
        src={movie.posterUrl ?? undefined}
      />

      <span className={styles.body}>
        <span className={styles.title}>{movie.title}</span>
        <span className={styles.meta}>
          {meta} · <span aria-hidden="true">★</span>
          <span className={styles.rating}>{movie.averageRating.toFixed(1)}</span>
        </span>
      </span>

      <span className={styles.otts}>
        {movie.ottProviders.length === 0 ? (
          <span className={styles.noOtt}>시청 가능한 OTT 없음</span>
        ) : (
          movie.ottProviders.map((code) => (
            <OttBadge key={code} code={code} name={ottNames[code] ?? code} size={20} />
          ))
        )}
      </span>
    </button>
  );
}
