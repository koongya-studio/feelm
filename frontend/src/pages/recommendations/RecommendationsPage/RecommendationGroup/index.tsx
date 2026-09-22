/*
 * 2.2 추천 영화의 데스크톱 추천 묶음 (업무 S15P21E106-783).
 *
 * 2026-09-21 시안 그대로다. **묶음 하나가 흰 패널이고, 그 안에 제목 줄과 검은 필름이 든다.**
 * 필름 안에서는 첫 카드가 크게 위에 놓이고 나머지가 작게 그 아래 두 열로 앉는다 — 서버가 준
 * 순서의 첫 카드가 그 묶음의 대표라는 뜻이다. 묶음 둘은 좌우로 선다.
 *
 * 좌우로 세우려면 두 묶음의 카드 수가 같아야 한다. 계약은 맞춤 둘 · 발견 하나를 주므로
 * 데스크톱 표시에서만 받은 카드를 석 장까지 되풀이한다. API 응답과 모바일 목록은 건드리지
 * 않는다.
 *
 * **모바일은 지금까지와 같다.** 412px에서는 묶음 이름표 없이 카드가 한 줄로 이어진다
 * (업무 -672). 넓은 화면은 두 묶음이 좌우로 놓여 이름 없이는 구분되지 않아 시안이 이름을
 * 되살렸다.
 */
import MovieBriefCard from '../../../../components/MovieBriefCard';
import FilmReel from '../../../../components/FilmReel';
import IconButton from '../../../../components/IconButton';
import { BlockIcon } from '../../../../components/Icon';
import styles from './RecommendationGroup.module.css';
import type { MouseEvent } from 'react';
import type { Ott } from '../../../../types/ott';
import type { RecommendationItem } from '../../../../types/recommendation';

/** 대표 카드와 나머지 카드의 포스터 폭. 시안의 카드에서 잰 값이다 */
const LEAD_POSTER_WIDTH = 128;
const REST_POSTER_WIDTH = 72;

/** OTT 로고 배지의 크기. 작은 카드는 폭이 좁아 한 단계 작다 */
const LEAD_OTT_SIZE = 30;
const REST_OTT_SIZE = 22;

/** 2026-09-21 데스크톱 시안이 묶음마다 보여 주는 카드 수 */
const DESIGN_CARD_COUNT = 3;

type Props = {
  title: string;
  description: string;
  items: RecommendationItem[];
  otts: Ott[];
  /** 지금 ⋯ 메뉴가 열려 있는 카드 */
  openMenuId: number | null;
  onOpenMenu: (event: MouseEvent<HTMLButtonElement>, item: RecommendationItem) => void;
  /** 관심없음을 처리 중인 카드. 그동안 그 카드의 버튼은 눌리지 않는다 */
  dismissingId: number | null;
  onDismiss: (item: RecommendationItem) => void;
};

export default function RecommendationGroup({
  title,
  description,
  items,
  otts,
  openMenuId,
  onOpenMenu,
  dismissingId,
  onDismiss,
}: Props) {
  if (items.length === 0) return null;

  /*
   * API-045는 현재 맞춤 둘 · 발견 하나를 준다. 서버 계약을 바꾸거나 모바일에 중복 카드를
   * 노출하지 않고 데스크톱 시안만 확인할 수 있도록 이 묶음 안에서만 석 장까지 채운다.
   * S15P21E106-793이 유형별 석 장을 주면 이 변환은 결과를 바꾸지 않는다.
   */
  const displayedItems =
    items.length >= DESIGN_CARD_COUNT
      ? items
      : Array.from({ length: DESIGN_CARD_COUNT }, (_, index) => items[index % items.length]);
  const [lead, ...rest] = displayedItems;

  /*
   * 카드 오른쪽 위 버튼 둘. 모바일 카드와 같은 동작이다(업무 -672) — 왼쪽이 관심없음(API-047),
   * 오른쪽이 OTT 메뉴(2.3)다. **시안의 하트(좋아요)는 이 앱에 없는 기능이라 그리지 않는다.**
   */
  function actions(item: RecommendationItem, size: number) {
    return (
      <>
        <IconButton
          label={`${item.movie.title} 관심없음`}
          icon={<BlockIcon />}
          size={size}
          shape="rounded"
          variant="plain"
          disabled={dismissingId === item.recommendationItemId}
          onClick={() => onDismiss(item)}
        />

        <IconButton
          label={`${item.movie.title} OTT 메뉴`}
          icon={<span className={styles.ellipsis}>⋯</span>}
          size={size}
          shape="rounded"
          variant="plain"
          aria-expanded={openMenuId === item.recommendationItemId}
          onClick={(event) => onOpenMenu(event, item)}
        />
      </>
    );
  }

  return (
    <section className={styles.group}>
      <header className={styles.head}>
        <h2 className={styles.title}>{title}</h2>
        <p className={styles.description}>{description}</p>
      </header>

      {/*
       * 필름 칸 둘 — 위는 대표 카드 하나, 아래는 나머지 카드다. 칸마다 카드를 하나씩 넣으면
       * 칸 크기가 모두 같아야 해서(`FilmReel` 기본 모드) 큰 카드와 작은 카드를 한 필름에
       * 넣을 수 없다.
       */}
      {/*
       * 구멍 띠가 위아래에 있는 가로 필름이다(시안). 칸 하나에 카드 줄을 통째로 넣는다 —
       * 칸마다 카드를 하나씩 넣으면 칸 크기가 모두 같아야 해서(`FilmReel` 기본 모드) 큰
       * 카드와 작은 카드를 한 필름에 넣을 수 없다.
       */}
      <FilmReel flat fit="content" tone="plain" label={`${title} 카드`}>
        <div className={styles.stack}>
          <div className={styles.lead}>
            <MovieBriefCard
              movie={lead.movie}
              otts={otts}
              posterWidth={LEAD_POSTER_WIDTH}
              ottSize={LEAD_OTT_SIZE}
              actions={actions(lead, 30)}
              reason={lead.reason.summary}
              detailButton
              recommendationItemId={lead.recommendationItemId}
            />
          </div>

          {rest.length > 0 ? (
            <div className={styles.rest}>
              {rest.map((item, index) => (
                <MovieBriefCard
                  key={`${item.recommendationItemId}-${index}`}
                  movie={item.movie}
                  otts={otts}
                  posterWidth={REST_POSTER_WIDTH}
                  ottSize={REST_OTT_SIZE}
                  actions={actions(item, 26)}
                  reason={item.reason.summary}
                  compact
                  recommendationItemId={item.recommendationItemId}
                />
              ))}
            </div>
          ) : null}
        </div>
      </FilmReel>
    </section>
  );
}
