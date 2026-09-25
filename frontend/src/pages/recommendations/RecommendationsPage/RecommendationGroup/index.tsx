/*
 * 2.2 추천 영화의 데스크톱 추천 묶음 (업무 S15P21E106-783).
 *
 * 2026-09-21 시안 그대로다. **묶음 하나가 흰 패널이고, 그 안에 제목 줄과 검은 필름이 든다.**
 * 필름 안에서는 첫 카드가 크게 위에 놓이고 나머지가 작게 그 아래 두 열로 앉는다 — 서버가 준
 * 순서의 첫 카드가 그 묶음의 대표라는 뜻이다. 묶음 둘은 좌우로 선다.
 *
 * 좌우로 세우려면 두 묶음의 카드 수가 같아야 한다. 화면이 여섯 편을 요청하면 계약이 맞춤 셋 ·
 * 발견 셋을 주므로(API-045 `size=6`, 업무 -793 · -794) 묶음마다 석 장이 그대로 선다.
 *
 * **모바일은 지금까지와 같다.** 412px에서는 묶음 이름표 없이 카드가 한 줄로 이어진다
 * (업무 -672). 넓은 화면은 두 묶음이 좌우로 놓여 이름 없이는 구분되지 않아 시안이 이름을
 * 되살렸다.
 */
import MovieBriefCard from '../../../../components/MovieBriefCard';
import FilmReel from '../../../../components/FilmReel';
import { BlockIcon } from '../../../../components/Icon';
import styles from './RecommendationGroup.module.css';
import type { Ott } from '../../../../types/ott';
import type { RecommendationItem } from '../../../../types/recommendation';

/** 대표 카드와 나머지 카드의 포스터 폭. 시안의 카드에서 잰 값이다 */
const LEAD_POSTER_WIDTH = 128;
const REST_POSTER_WIDTH = 72;

/** OTT 로고 배지의 크기. 작은 카드는 폭이 좁아 한 단계 작다 */
const LEAD_OTT_SIZE = 30;
const REST_OTT_SIZE = 22;

/** 2026-09-21 데스크톱 시안이 묶음마다 보여 주는 카드 수. 더 와도 이만큼만 그린다 */
const DESIGN_CARD_COUNT = 3;

type Props = {
  title: string;
  description: string;
  items: RecommendationItem[];
  otts: Ott[];
  /** 관심없음을 처리 중인 카드. 그동안 그 카드의 버튼은 눌리지 않는다 */
  dismissingId: number | null;
  onDismiss: (item: RecommendationItem) => void;
};

export default function RecommendationGroup({
  title,
  description,
  items,
  otts,
  dismissingId,
  onDismiss,
}: Props) {
  if (items.length === 0) return null;

  /*
   * **앞에서부터 석 장까지만 그린다**(사용자 결정 2026-09-22, 업무 -794).
   *
   * -783은 계약이 맞춤 둘 · 발견 하나를 주던 동안 시안의 석 장을 채우려고 이 자리에서 카드를
   * 되풀이했다. 이제 여섯 편을 요청하면 유형별로 석 장이 오므로 되풀이를 걷어냈다 — PC에서
   * 같은 영화가 두 번 서던 것이 사라진다.
   *
   * 취향 발견 후보가 모자라면 그 자리를 맞춤이 채워(업무 -793) 한 묶음에 넷 이상이 올 수 있다.
   * 그때도 묶음은 석 장이다 — 시안의 한 칸(대표 하나 + 아래 둘)이 묶음의 모양이고, 여기서
   * 줄을 늘리면 좌우 두 묶음의 길이가 갈린다.
   */
  const displayedItems = items.slice(0, DESIGN_CARD_COUNT);
  const [lead, ...rest] = displayedItems;

  /*
   * 모든 추천 카드는 관심없음만 표시한다(업무 -890). 대표 카드는 카드 크기에 맞춰 한 단계
   * 크게 두고, 작은 카드는 작게 둔다. 시안의 하트는 앱에 없는 기능이라 그리지 않는다.
   *
   * **아이콘 옆에 `관심없음` 글자를 둔다**(업무 -873). 모바일 카드 · 2.4 영화 상세와 같이
   * 아이콘만으로 두지 않는다. 흰 카드 위라 면은 두지 않고 글자와 아이콘 색만 쓴다.
   */
  function actions(item: RecommendationItem, lead = false) {
    return (
      <button
        type="button"
        className={styles.dismissButton}
        data-size={lead ? 'lead' : undefined}
        aria-label={`${item.movie.title} 관심없음`}
        disabled={dismissingId === item.recommendationItemId}
        onClick={() => onDismiss(item)}
      >
        <BlockIcon />
        <span aria-hidden="true">관심없음</span>
      </button>
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
              actions={actions(lead, true)}
              reason={lead.reason.summary}
              detailButton
              recommendationItemId={lead.recommendationItemId}
              ottLinks
            />
          </div>

          {rest.length > 0 ? (
            <div className={styles.rest}>
              {rest.map((item) => (
                <MovieBriefCard
                  key={item.recommendationItemId}
                  movie={item.movie}
                  otts={otts}
                  posterWidth={REST_POSTER_WIDTH}
                  ottSize={REST_OTT_SIZE}
                  actions={actions(item)}
                  reason={item.reason.summary}
                  compact
                  recommendationItemId={item.recommendationItemId}
                  ottLinks
                />
              ))}
            </div>
          ) : null}
        </div>
      </FilmReel>
    </section>
  );
}
