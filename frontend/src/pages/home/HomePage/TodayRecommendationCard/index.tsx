/*
 * 2.1 오늘의 추천 — 대표 카드 (MovieCard 대표형)
 *
 * common-components.md 4.4에 따라 MovieCard의 네 형태를 한 컴포넌트의 분기로 만들지
 * 않는다. 대표형은 2.1에서만 쓰이므로 화면 폴더에 둔다. 두 번째 화면이 필요해지면
 * src/components/로 올린다.
 *
 * 카드 전체를 링크로 만들지 않고 포스터와 제목만 링크로 둔다. 와이어프레임의
 * `추천 더 보기` 버튼이 카드 안에 함께 있어 카드 전체를 링크로 감싸면 중첩된다.
 *
 * `취향 일치 92%`(matchRate)와 `128분`(runtimeMinutes)은 계약에 없는 값이다.
 * types/unspecified.ts를 참고한다.
 */
import { Link } from 'react-router';
import Badge from '../../../../components/Badge';
import Chip from '../../../../components/Chip';
import OttBadge from '../../../../components/OttBadge';
import Poster from '../../../../components/Poster';
import { path } from '../../../../routes/paths';
import { ottBadgeLetter } from '../../../../utils/ott';
import styles from './TodayRecommendationCard.module.css';
import type { Ott } from '../../../../types/ott';
import type { RecommendationItemView } from '../../../../types/unspecified';

type Props = {
  item: RecommendationItemView;
  otts: Ott[];
};

export default function TodayRecommendationCard({ item, otts }: Props) {
  const { movie, reason, matchRate } = item;
  const meta = [...movie.genres, `${movie.runtimeMinutes}분`].join(' · ');

  return (
    <div className={styles.card}>
      <Link
        to={path.movieDetail(movie.movieId)}
        className={styles.posterLink}
        aria-label={`${movie.title} 상세 보기`}
      >
        {/* 링크가 이름을 제공하므로 포스터의 대체 텍스트는 비운다 */}
        <Poster width={104} height={148} radius={8} src={movie.posterUrl ?? undefined} title="" />
      </Link>

      <div className={styles.info}>
        <p className={styles.title}>{movie.title}</p>
        <p className={styles.meta}>{meta}</p>

        <span className={styles.badgeRow}>
          <Badge size="md">취향 일치 {matchRate}%</Badge>
        </span>

        {/*
         * `아직 안 본 영화`는 고정 문구다. API-045가 감상 기록이 있는 영화를 응답에서
         * 제외하므로(처리 규칙) 추천 카드의 영화는 모두 안 본 영화다.
         * `[?]` 배지 표시 규칙 자체는 명세에 없다(screen-spec.md 2.1 미확정).
         */}
        <span className={styles.badgeRow}>
          <Chip>아직 안 본 영화</Chip>
        </span>

        <p className={styles.reason}>{reason.summary}</p>

        <ul className={styles.ottList}>
          {movie.ottProviders.map((code) => {
            const ott = otts.find((candidate) => candidate.code === code);

            return (
              <li key={code}>
                <OttBadge letter={ottBadgeLetter(code)} name={ott?.name ?? code} size={22} />
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
