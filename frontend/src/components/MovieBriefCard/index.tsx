/*
 * 데스크톱 영화 카드 (업무 S15P21E106-783).
 *
 * 2026-09-21 시안의 취향 필름 `오늘의 추천`과 추천 영화 화면의 카드는 같은 모양이다.
 * **포스터가 왼쪽, 오른쪽에 제목 · 개봉연도 · 장르 · 평점 · 추천 이유 · OTT 배지**가 선다.
 * 큰 카드와 작은 카드의 차이는 포스터 폭과 아래 버튼의 유무뿐이라 한 컴포넌트로 둔다 —
 * 두 화면이 각자 그리면 같은 카드가 조금씩 다른 두 벌이 된다(Jira -783 완료 조건).
 *
 * **모바일 카드(`RecommendationsPage/RecommendationCard`)를 바꾸지 않는다.** 그쪽은 포스터
 * 위에 이유를 겹쳐 놓는 세로 카드이고 412px 이하에서 그대로 남는다(Jira -783 완료 조건 —
 * 412px 이하 모바일 UI 유지).
 *
 * 평점은 계약의 `averageRating` 그대로 5점 만점이다(API-045). 시안은 8.6처럼 10점처럼
 * 보이는 수를 적지만, 이 앱의 평가는 0.5~5.0이고(FR-19) 영화 상세도 5점으로 적는다.
 * 같은 영화가 화면마다 다른 수로 보이지 않게 계약 값을 그대로 쓴다.
 *
 * 평가가 하나도 없으면 계약이 `averageRating: 0`을 준다(API-030 · 업무 -771). 그때는 별을
 * 그리지 않는다 — `★ 0.0`은 모두가 싫어한 영화라는 뜻으로 읽힌다.
 */
import { Link } from 'react-router';
import { PlayIcon } from '../Icon';
import OttBadge from '../OttBadge';
import Poster from '../Poster';
import PosterCue from '../PosterCue';
import { path } from '../../routes/paths';
import styles from './MovieBriefCard.module.css';
import type { CSSProperties, ReactNode } from 'react';
import type { Ott } from '../../types/ott';
import type { RecommendedMovie } from '../../types/movie';

/** 포스터 비율. 앱의 모든 포스터가 2:3이다 */
const POSTER_RATIO = 3 / 2;

type Props = {
  movie: RecommendedMovie;
  otts: Ott[];
  /** 포스터 폭(px). 높이는 2:3으로 따라간다 */
  posterWidth: number;
  /** OTT 배지 한 변의 길이(px). 시안의 카드 크기에 맞춘다 */
  ottSize?: number;
  /**
   * 작은 카드 (업무 -783). 시안이 대표 카드 아래에 두 열로 앉히는 카드이며, 폭이 절반이라
   * 안쪽 여백과 글자가 한 단계 작다.
   */
  compact?: boolean;
  /**
   * 카드 아래 `영화 상세 보기` 버튼 (업무 -783).
   *
   * **모든 카드가 같은 빨강이다**(사용자 결정 2026-09-22). 시안은 첫 카드만 빨강이고
   * 나머지는 흰 면이지만, 시안에서 두 버튼은 서로 다른 동작(`지금 바로 보기` · `자세히 보기`)
   * 이었다. 이 앱은 OTT 재생 주소가 추천 응답에 없어(API-030에만 있다) 모든 버튼이 같은
   * 곳으로 가므로, 같은 동작에 두 가지 무게를 주지 않는다.
   */
  detailButton?: boolean;
  /** 카드 왼쪽 위 표식. 추천 종류(`맞춤 추천` · `취향 확장`)를 말한다 */
  badge?: ReactNode;
  /** 카드 오른쪽 위에 놓이는 조작 버튼 */
  actions?: ReactNode;
  /** 추천 이유 한 줄. 없으면 그 줄을 비운다 */
  reason?: string;
  /** 카드 아래 버튼. 큰 카드에만 준다 */
  footer?: ReactNode;
  /**
   * 어느 추천에서 왔는지. 영화 상세가 추천 이유를 함께 보여줄 때 쓴다(API-030
   * `recommendation`). 추천이 아닌 곳에서 쓰면 생략한다
   */
  recommendationItemId?: number;
};

export default function MovieBriefCard({
  movie,
  otts,
  posterWidth,
  ottSize = 26,
  compact = false,
  badge,
  actions,
  reason,
  footer,
  detailButton,
  recommendationItemId,
}: Props) {
  const detail = path.movieDetail(movie.movieId);
  const ottRowStyle = { '--ott-row-height': `${ottSize}px` } as CSSProperties;
  /*
   * 작은 카드는 장르를 하나만 적는다. 이 앱의 중앙 단은 시안 캔버스보다 좁아 카드 한 장이
   * 165px인데, 장르를 다 적으면 개봉연도와 장르가 두 줄로 갈린다. 시안은 이 줄이 한 줄이다.
   */
  const genres = compact ? movie.genres.slice(0, 1) : movie.genres;

  /*
   * OTT 로고 줄.
   *
   * **작은 카드는 포스터 아래 한 줄로 내린다**(시안). 오른쪽 글 칸에 두면 좁은 칸을 로고가
   * 다시 나눠 두 줄로 접힌다.
   *
   * **볼 수 있는 곳이 없으면 그렇다고 적는다**(사용자 결정 2026-09-22). 줄을 비워 두기만
   * 하면 그 자리가 왜 비었는지 알 수 없고, 줄을 통째로 지우면 그 카드만 짧아진다.
   */
  const ottRow =
    movie.ottProviders.length > 0 ? (
      <ul className={styles.ottList} style={ottRowStyle}>
        {movie.ottProviders.map((code) => {
          const ott = otts.find((candidate) => candidate.code === code);

          return (
            <li key={code}>
              <OttBadge code={code} name={ott?.name ?? code} size={ottSize} />
            </li>
          );
        })}
      </ul>
    ) : (
      <p className={styles.ottEmpty} style={ottRowStyle}>
        제공 중인 OTT가 없어요
      </p>
    );
  const linkState = recommendationItemId === undefined ? undefined : { recommendationItemId };

  return (
    <article className={styles.card} data-compact={compact || undefined}>
      {/*
       * 표식 줄. 표식도 버튼도 없는 카드에서도 자리를 지킨다 — 줄을 없애면 그 카드만
       * 짧아져 한 줄에 선 카드들의 아랫변이 어긋난다.
       */}
      <div className={styles.head}>
        {badge ? <span className={styles.badge}>{badge}</span> : null}
        {actions ? <span className={styles.actions}>{actions}</span> : null}
      </div>

      <div className={styles.body}>
        <Link
          to={detail}
          state={linkState}
          className={styles.posterLink}
          data-guide-target="recommendation"
          aria-label={`${movie.title} 상세 보기`}
        >
          <PosterCue cue={false}>
            <Poster
              width={posterWidth}
              height={Math.round(posterWidth * POSTER_RATIO)}
              radius={0}
              src={movie.posterUrl ?? undefined}
              title=""
              seed={movie.movieId}
            />
          </PosterCue>
        </Link>

        <div className={styles.info}>
          <h3 className={styles.title}>
            <Link to={detail} state={linkState} className={styles.titleLink}>
              {movie.title}
            </Link>
          </h3>

          <p className={styles.meta}>
            <span>{movie.releaseYear}</span>
            {genres.length > 0 ? (
              <>
                <span className={styles.metaBar} aria-hidden="true">
                  |
                </span>
                <span>{genres.join(' · ')}</span>
              </>
            ) : null}
          </p>

          {/*
           * 평점 줄. **평가가 없어도 자리는 비워 둔다.**
           *
           * 계약은 평가가 하나도 없으면 `averageRating: 0`을 주는데(API-030 · 업무 -771)
           * `★ 0.0`은 모두가 싫어한 영화라는 뜻으로 읽히므로 그리지 않는다. 그렇다고 줄을
           * 없애면 그 카드만 한 줄 짧아져 옆 카드와 아랫변이 어긋난다(사용자 지적 2026-09-22).
           */}
          <p className={styles.rating}>
            {movie.averageRating > 0 ? (
              <>
                <span className={styles.star} aria-hidden="true">
                  ★
                </span>
                <span className={styles.ratingValue}>{movie.averageRating.toFixed(1)}</span>
                <span className={styles.ratingUnit}>점</span>
              </>
            ) : null}
          </p>

          {/* 추천 이유도 같다. 서버가 문구를 주지 않아도 줄 수만큼 자리를 지킨다 */}
          <p className={styles.reason}>{reason}</p>

          {compact ? null : ottRow}
        </div>
      </div>

      {compact ? ottRow : null}

      {detailButton ? (
        <Link to={detail} state={linkState} className={styles.detailButton}>
          <PlayIcon />
          영화 상세 보기
        </Link>
      ) : null}

      {footer ? <div className={styles.footer}>{footer}</div> : null}
    </article>
  );
}
