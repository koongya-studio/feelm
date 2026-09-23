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
 * 추천 이유 상자는 API-045의 `reason.summary`만 표시한다(업무 -822). `추천 이유:` 같은
 * 고정 라벨을 덧붙이지 않아 서버가 확정한 사용자용 문장을 그대로 읽을 수 있게 한다.
 *
 * **배지는 취향 발견 카드의 `새로운 맛` 하나다**(사용자 지시 2026-09-18, 업무 -672).
 * 섹션 이름표를 뺐으므로 어느 카드가 새로운 맛을 여는 추천인지는 이 배지가 말한다. 취향 맞춤
 * 카드의 `안 봄`은 뺐다. `일치 %` · `연결 %`(matchRate)도 계약에 없어 그리지 않는다
 * (CR-13, 업무 -610).
 *
 * **관심없음은 ⋯ 메뉴가 아니라 오른쪽 위 버튼이다**(사용자 지시 2026-09-18, 업무 -672).
 * 누르면 화면이 API-047을 부른다(`RecommendationsPage`). 처리하는 동안은 눌리지 않는다.
 *
 * **포스터가 상세로 간다는 단서는 추천 이유 상자 끝의 화살표다**(업무 -697). 다른 포스터는
 * 오른쪽 아래에 화살표를 얹지만(`PosterCue`) 이 카드는 그 자리를 추천 이유가 덮는다. 상자는
 * 누름을 가로채지 않아(`pointer-events: none`) 상자 위를 눌러도 상세로 간다.
 */
import { Link } from 'react-router';
import Badge from '../../../../components/Badge';
import IconButton from '../../../../components/IconButton';
import OttBadge from '../../../../components/OttBadge';
import { BlockIcon, ChevronRightIcon } from '../../../../components/Icon';
import Poster from '../../../../components/Poster';
import PosterCue from '../../../../components/PosterCue';
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
        <PosterCue cue={false}>
          <Poster
            width={156}
            height={220}
            fluid
            radius={0}
            src={movie.posterUrl ?? undefined}
            title=""
            seed={movie.movieId}
          />
        </PosterCue>
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
        <span className={styles.reasonText}>{reason.summary}</span>
        <span className={styles.reasonCue} aria-hidden="true">
          <ChevronRightIcon />
        </span>
      </p>
    </div>
  );
}
