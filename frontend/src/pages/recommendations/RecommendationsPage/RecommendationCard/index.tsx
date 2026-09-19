/*
 * 2.2 오늘의 추천 — 카드 (MovieCard 목록형)
 *
 * **크기는 릴의 칸을 채운다** — 필름이 화면 폭을 꽉 채우도록 칸이 화면 폭을 따른다(업무 -672,
 * `RecommendationsPage`의 FRAME_WIDTH). 비율은 와이어프레임 156 × 220이고 아래 위치값도
 * 와이어프레임 그대로다.
 * - `⋯` 버튼 26px: top 6 left 6, 반투명 흰 배경
 * - 관심없음 버튼 26px: top 6 right 6 — ⋯ 버튼과 같은 모양(업무 -672)
 * - OTT 배지 22px: 관심없음 버튼 왼쪽, gap 4
 * - `새로운 맛` 배지: ⋯ 버튼 오른쪽(top 6 · left 38, 버튼 높이 가운데), 빨간 면 —
 *   취향 발견 카드에만(사용자 지시 2026-09-18)
 * - 추천 이유 상자: left 6 right 6 bottom 6
 *
 * 추천 이유 상자는 와이어프레임에서 유형별로 다르게 그려져 있다. 취향 맞춤 카드는
 * `추천 이유:` 라벨과 한 줄, 취향 발견 카드는 라벨 없이 문장 전체다. 그대로 따른다.
 *
 * **배지는 취향 발견 카드의 `새로운 맛` 하나다**(사용자 지시 2026-09-18, 업무 -672).
 * 섹션 이름표를 뺐으므로 어느 카드가 새로운 맛을 여는 추천인지는 이 배지가 말한다. 취향 맞춤
 * 카드의 `안 봄`은 뺐다. `일치 %` · `연결 %`(matchRate)도 계약에 없어 그리지 않는다
 * (CR-13, 업무 -610).
 *
 * **관심없음은 ⋯ 메뉴가 아니라 오른쪽 위 버튼이다**(사용자 지시 2026-09-18, 업무 -672).
 * 누르면 화면이 API-047을 부른다(`RecommendationsPage`). 처리하는 동안은 눌리지 않는다.
 */
import { Link } from 'react-router';
import Badge from '../../../../components/Badge';
import IconButton from '../../../../components/IconButton';
import OttBadge from '../../../../components/OttBadge';
import { BlockIcon } from '../../../../components/Icon';
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
  /** 관심없음을 처리하는 중이면 버튼을 막는다 */
  dismissing: boolean;
  onDismiss: (item: RecommendationItem) => void;
};

export default function RecommendationCard({
  item,
  otts,
  menuOpen,
  onOpenMenu,
  dismissing,
  onDismiss,
}: Props) {
  const { movie, reason, type } = item;
  const isDiscovery = type === 'DISCOVERY';

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
          fluid
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

      <span className={styles.dismissButton}>
        <IconButton
          label={`${movie.title} 관심없음`}
          icon={<BlockIcon />}
          size={26}
          shape="rounded"
          variant="translucent"
          disabled={dismissing}
          onClick={() => onDismiss(item)}
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

      {isDiscovery ? (
        <span className={styles.badge}>
          <Badge tone="accent">새로운 맛</Badge>
        </span>
      ) : null}

      <p className={styles.reason} data-multiline={isDiscovery}>
        {isDiscovery ? null : <span className={styles.reasonLabel}>추천 이유: </span>}
        {reason.summary}
      </p>
    </div>
  );
}
