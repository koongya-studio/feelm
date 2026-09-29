/*
 * 3.1 취향 필름의 `오늘의 추천` (업무 S15P21E106-783).
 *
 * 2026-09-21 시안은 데스크톱 취향 필름 화면의 필름 아래에 오늘의 추천 카드를 늘어놓는다.
 * 넓은 화면에는 필름만으로 채울 수 없는 자리가 남고, 모바일에서 `필름 늘리기` 버튼 하나로
 * 가던 2.2를 여기서 바로 보여준다. **넓은 폭에서만 그린다** — 412px 이하 화면은 지금까지와
 * 같이 필름과 버튼이다(`FilmPage`).
 *
 * **2.2 추천 영화 화면과 같은 것이다.** 화면 이름이 `오늘의 추천`이고 데이터도 같은 API-045다
 * (사용자 확인 2026-09-22). 새로 만든 기능이 아니라 그 화면의 카드를 여기서 먼저 보여주는
 * 것이며, 카드는 2.2와 같은 컴포넌트를 쓴다(`components/MovieBriefCard`, Jira -783 완료
 * 조건 — 공유 가능한 영화 카드를 중복 없이 구성한다).
 *
 * **카드 수를 정해 두지 않는다.** 시안은 석 장을 그리고 계약도 지금은 맞춤 둘 · 발견 하나를
 * 주지만(API-045), 개수는 서버가 정한다. 제목 옆의 `맞춤 N : 확장 M`도 받은 카드를 세어 적는다.
 *
 * **시안의 하트(좋아요)는 그리지 않는다.** 이 앱에 좋아요가 없다 — 추천 카드에서 할 수 있는
 * 것은 관심없음(API-047)이고 그것은 2.2 화면의 동작이다. 없는 기능을 이 업무에서 새로
 * 만들지 않는다.
 *
 * 받지 못하면 이 자리를 비운다. 이 화면의 본문은 필름이고 추천은 그 아래 딸린 자리다.
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import Badge from '../../../../components/Badge';
import MovieBriefCard from '../../../../components/MovieBriefCard';
import { ChevronRightIcon } from '../../../../components/Icon';
import { getOtts } from '../../../../api/otts';
import { getRecommendations } from '../../../../api/recommendations';
import { path } from '../../../../routes/paths';
import styles from './TodayRecommendations.module.css';
import type { Ott } from '../../../../types/ott';
import type { RecommendationItem } from '../../../../types/recommendation';

/*
 * 카드 안 포스터의 폭. 우측 보조 패널이 있는 1440px에서 중앙 단은 790px쯤이고, 카드 셋이
 * 나란히 서면 한 장이 245px다. 포스터를 더 키우면 옆의 추천 이유가 두 줄에 들어가지 못하고
 * 문장 가운데서 잘린다(2026-09-22 브라우저 확인).
 */
const POSTER_WIDTH = 72;

/** OTT 로고 배지의 크기. 추천 영화 화면의 카드보다 좁아 한 단계 작다 */
const OTT_BADGE_SIZE = 28;

/** 이 자리에 세우는 카드 수. 시안이 석 장을 그린다 — 나머지는 2.2에서 본다 */
const CARD_COUNT = 3;

export default function TodayRecommendations() {
  const [items, setItems] = useState<RecommendationItem[] | null>(null);
  const [otts, setOtts] = useState<Ott[]>([]);

  useEffect(() => {
    let active = true;

    getRecommendations()
      .then((set) => {
        if (active) setItems(set.items);
      })
      .catch(() => undefined);

    // OTT 기준정보를 받지 못해도 배지는 코드로 그린다
    getOtts()
      .then((list) => {
        if (active) setOtts(list);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  if (!items || items.length === 0) return null;

  /* 유형별로 앞에서부터 고른다. 맞춤만 석 장 서고 확장이 빠지는 일이 없게 한다 */
  const shown = [
    ...items.filter((item) => item.type === 'TASTE').slice(0, CARD_COUNT - 1),
    ...items.filter((item) => item.type !== 'TASTE').slice(0, 1),
  ].slice(0, CARD_COUNT);
  /* 세는 것은 이 자리에 세운 카드다. 2.2가 더 보여 주는 수를 여기서 말하지 않는다 */
  const tasteCount = shown.filter((item) => item.type === 'TASTE').length;
  const discoveryCount = shown.length - tasteCount;

  return (
    <section className={styles.section}>
      <div className={styles.head}>
        <h2 className={styles.title}>나를 위한 영화 추천</h2>

        {/*
         * 2.2로 가는 상시 진입점. 모바일에서 `필름 늘리기` 버튼이 하던 일이며, 넓은 화면에서는
         * 그 버튼을 빼는 대신(사용자 결정 2026-09-22) 이 줄이 같은 곳으로 간다.
         */}
        <Link to={path.recommendations()} className={styles.more}>
          맞춤 {tasteCount} : 확장 {discoveryCount}
          <ChevronRightIcon />
        </Link>
      </div>

      <ul className={styles.cards}>
        {shown.map((item, index) => (
          <li key={`${item.recommendationItemId}-${index}`}>
            <MovieBriefCard
              movie={item.movie}
              otts={otts}
              posterWidth={POSTER_WIDTH}
              ottSize={OTT_BADGE_SIZE}
              badge={
                <Badge tone={item.type === 'DISCOVERY' ? 'accent' : 'default'}>
                  {item.type === 'DISCOVERY' ? '취향 확장' : '맞춤 추천'}
                </Badge>
              }
              reason={item.reason.summary}
              detailButton
              recommendationItemId={item.recommendationItemId}
              ottLinks
            />
          </li>
        ))}
      </ul>
    </section>
  );
}
