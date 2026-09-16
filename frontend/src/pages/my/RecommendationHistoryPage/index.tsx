/*
 * 7.5 추천 기록
 *
 * 이동표: 7.1 → 7.5 추천 기록 · 7.5 ⇢ 2.4 추천 기록의 영화 선택
 *
 * 요소는 월 제목과 영화 줄(포스터 · 제목 · 날짜 · 추천 출처)뿐이다. 연동 API는 API-048
 * 추천 이력 조회다.
 *
 * - **출처 표시는 계약의 `type`이다.** `TASTE` 취향 맞춤 · `DISCOVERY` 새로운 맛 발견 ·
 *   `PARTY` 파티 추천. 와이어프레임의 세 문구가 세 값과 하나씩 맞는다
 * - **월로 묶는다.** 계약은 세트 단위로 주고 세트마다 `generatedAt`이 있다. 같은 달의
 *   세트를 한 제목 아래 모은다
 * - **관심없음 처리한 영화도 목록에 남는다**(API-048 처리 규칙 `dismissed: true`). 표시는
 *   하지 않는다 — 와이어프레임에 없던 표시다(업무 -459)
 *
 * 뺀 것(업무 -459) — 날짜 옆 OTT는 API-048 `movie`에 없어 목업이 지어내던 값이고,
 * `총 N편을 추천받았어요`의 N은 계약에 총수가 없어 받은 목록을 센 값이었다.
 *
 * `[?]` 헤더 옆의 `· 최신순 · 추천 출처 표시`는 화면 설명으로 보고 옮기지 않았다.
 *
 * 어두운 블록은 없다. 포스터가 이 화면의 콘텐츠이고 나머지는 벽이다.
 */
import { useEffect, useState } from 'react';
import Button from '../../../components/Button';
import MovieRow from '../../../components/MovieRow';
import ScreenHeader from '../../../components/ScreenHeader';
import StatusView from '../../../components/StatusView';
import { getRecommendationHistory } from '../../../api/recommendations';
import { path } from '../../../routes/paths';
import { formatShortDate, formatYearMonth } from '../../../utils/date';
import styles from './RecommendationHistoryPage.module.css';
import type { RecommendationType } from '../../../types/enums';
import type {
  RecommendationHistory,
  RecommendationHistoryItem,
} from '../../../types/recommendation';

/** 와이어프레임 7.5의 출처 문구 */
const SOURCE_LABEL: Record<RecommendationType, string> = {
  TASTE: '취향 맞춤',
  DISCOVERY: '새로운 맛 발견',
  PARTY: '파티 추천',
};

type MonthGroup = {
  label: string;
  rows: { item: RecommendationHistoryItem; generatedAt: string }[];
};

/** 세트는 최신순으로 오므로 순서대로 훑으며 달이 바뀔 때 새 묶음을 연다 */
function groupByMonth(history: RecommendationHistory): MonthGroup[] {
  const groups: MonthGroup[] = [];

  for (const set of history.items) {
    const label = formatYearMonth(set.generatedAt);
    let group = groups.at(-1);

    if (!group || group.label !== label) {
      group = { label, rows: [] };
      groups.push(group);
    }

    group.rows.push(...set.items.map((item) => ({ item, generatedAt: set.generatedAt })));
  }

  return groups;
}

export default function RecommendationHistoryPage() {
  const [history, setHistory] = useState<RecommendationHistory | null>(null);
  const [failed, setFailed] = useState(false);
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    let active = true;

    getRecommendationHistory()
      .then((value) => {
        if (active) {
          setHistory(value);
          setFailed(false);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
    };
  }, [reloadCount]);

  const header = <ScreenHeader title="추천 기록" showBack />;

  if (failed) {
    return (
      <>
        {header}

        <StatusView title="추천 기록을 불러오지 못했어요" description="잠시 후 다시 시도해 주세요">
          <Button
            onClick={() => {
              setFailed(false);
              setReloadCount((count) => count + 1);
            }}
          >
            다시 시도
          </Button>
        </StatusView>
      </>
    );
  }

  if (!history) {
    return header;
  }

  const groups = groupByMonth(history);
  const total = groups.reduce((sum, group) => sum + group.rows.length, 0);

  if (total === 0) {
    return (
      <>
        {header}

        <StatusView
          variant="empty"
          title="아직 추천받은 영화가 없어요"
          description="홈에서 오늘의 추천을 받아 보세요"
        />
      </>
    );
  }

  return (
    <>
      {header}

      <div className={styles.page}>
        {groups.map((group) => (
          <section key={group.label} className={styles.month}>
            <h2 className={styles.monthLabel}>{group.label}</h2>

            <ul className={styles.list}>
              {group.rows.map(({ item, generatedAt }) => (
                <li key={item.recommendationItemId}>
                  <MovieRow movie={item.movie} to={path.movieDetail(item.movie.movieId)}>
                    <span className={styles.meta}>{formatShortDate(generatedAt)}</span>

                    <span className={styles.source}>{SOURCE_LABEL[item.type]}</span>
                  </MovieRow>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </>
  );
}
