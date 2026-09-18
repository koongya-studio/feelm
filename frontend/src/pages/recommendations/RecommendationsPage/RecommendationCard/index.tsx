/*
 * 2.2 오늘의 추천 — 카드 (MovieCard 목록형)
 *
 * 와이어프레임 실측값: 156x220 · radius 4
 * - OTT 배지 22px: top 6 right 6, gap 4
 * - `⋯` 버튼 26px: top 6 left 6, 반투명 흰 배경
 * - 배지: top 38 left 6 — `일치 92% · 안 봄` / `연결 72% · 새로운 맛`
 * - 추천 이유 상자: left 6 right 6 bottom 6
 *
 * 추천 이유 상자는 와이어프레임에서 유형별로 다르게 그려져 있다. 취향 맞춤 카드는
 * `추천 이유:` 라벨과 한 줄, 취향 발견 카드는 라벨 없이 문장 전체다. 그대로 따른다.
 *
 * `일치 %` · `연결 %`(matchRate)는 계약에 없어 그리지 않는다(CR-13, 업무 -610). `안 봄`은 API-045가 감상 기록이
 * 있는 영화를 제외하므로 고정 문구로 둔다. 판정 규칙 자체는 미확정이다.
 */
import { Link } from 'react-router';
import Badge from '../../../../components/Badge';
import IconButton from '../../../../components/IconButton';
import OttBadge from '../../../../components/OttBadge';
import Poster from '../../../../components/Poster';
import { path } from '../../../../routes/paths';
import styles from './RecommendationCard.module.css';
import type { MouseEvent } from 'react';
import type { Ott } from '../../../../types/ott';
import type { RecommendationItem } from '../../../../types/recommendation';

type Props = {
  item: RecommendationItem;
  otts: Ott[];
  menuOpen: boolean;
  onOpenMenu: (event: MouseEvent<HTMLButtonElement>, item: RecommendationItem) => void;
};

export default function RecommendationCard({ item, otts, menuOpen, onOpenMenu }: Props) {
  const { movie, reason, type } = item;
  const isDiscovery = type === 'DISCOVERY';
  // 일치도 %는 계약에 없다(CR-13). 서버가 주지 않는 수치를 지어내지 않는다
  const badgeText = isDiscovery ? '새로운 맛' : '안 봄';

  return (
    <div className={styles.card}>
      <Link
        to={path.movieDetail(movie.movieId)}
        state={{ recommendationItemId: item.recommendationItemId }}
        className={styles.posterLink}
        aria-label={`${movie.title} 상세 보기`}
      >
        <Poster
          width={156}
          height={220}
          radius={0}
          src={movie.posterUrl ?? undefined}
          title=""
          seed={movie.movieId}
        />
      </Link>

      <span className={styles.menuButton}>
        <IconButton
          label={`${movie.title} OTT 메뉴`}
          icon={<span className={styles.ellipsis}>⋯</span>}
          size={26}
          shape="rounded"
          variant={menuOpen ? 'filled' : 'translucent'}
          aria-expanded={menuOpen}
          onClick={(event) => onOpenMenu(event, item)}
        />
      </span>

      <ul className={styles.ottList}>
        {movie.ottProviders.map((code) => {
          const ott = otts.find((candidate) => candidate.code === code);

          return (
            <li key={code}>
              <OttBadge code={code} name={ott?.name ?? code} size={22} />
            </li>
          );
        })}
      </ul>

      <span className={styles.badge}>
        <Badge>{badgeText}</Badge>
      </span>

      <p className={styles.reason} data-multiline={isDiscovery}>
        {isDiscovery ? null : <span className={styles.reasonLabel}>추천 이유: </span>}
        {reason.summary}
      </p>
    </div>
  );
}
