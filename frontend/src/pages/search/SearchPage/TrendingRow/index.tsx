/*
 * 4.1 급상승 영화 한 줄.
 *
 * 순위 · 제목 · `순위변동 · 평가 N · ★ 점수` · OTT 배지다.
 *
 * 순위 변동은 와이어프레임이 `▲2` · `▼1` · `—` 세 모양으로 그렸다. 계약(API-064)은
 * `rankDelta` 정수 하나이므로 부호로 셋을 만든다. 색은 쓰지 않는다 — 상승·하락에 빨강과
 * 파랑을 쓰면 UI에 유채색이 늘어난다(`design-concept.md` 2.2).
 *
 * `[?]` API-064 응답에는 OTT 정보가 없는데 와이어프레임은 배지를 붙인다. 배지 코드는
 * 화면 밖에서 채워 넣는다(`mocks/trending.ts` 머리말).
 */
import OttBadge from '../../../../components/OttBadge';
import { ottBadgeLetter } from '../../../../utils/ott';
import type { TrendingItem } from '../../../../types/trend';
import styles from './TrendingRow.module.css';

type Props = {
  item: TrendingItem;
  /** 제공 OTT 코드. 계약에 없어 화면 밖에서 채운다 */
  ottProviders?: string[];
  /** OTT 코드 → 표시 이름 */
  ottNames?: Record<string, string>;
  onSelect: () => void;
};

/** `▲2` · `▼1` · `—`. 보조기술에는 뜻을 풀어 읽힌다 */
function deltaLabel(rankDelta: number) {
  if (rankDelta > 0) return { mark: `▲${rankDelta}`, text: `${rankDelta}단계 상승` };
  if (rankDelta < 0)
    return { mark: `▼${Math.abs(rankDelta)}`, text: `${Math.abs(rankDelta)}단계 하락` };

  return { mark: '—', text: '순위 변동 없음' };
}

export default function TrendingRow({ item, ottProviders = [], ottNames = {}, onSelect }: Props) {
  const delta = deltaLabel(item.rankDelta);

  return (
    <button type="button" className={styles.row} onClick={onSelect}>
      <span className={styles.rank}>{item.rank}</span>

      <span className={styles.body}>
        <span className={styles.title}>{item.movie.title}</span>
        <span className={styles.meta}>
          <span aria-hidden="true">{delta.mark}</span>
          <span className={styles.srOnly}>{delta.text}</span> · 평가 {item.ratingCount} ·{' '}
          <span aria-hidden="true">★</span>
          <span className={styles.score}>{item.averageScore.toFixed(1)}</span>
        </span>
      </span>

      <span className={styles.otts}>
        {ottProviders.map((code) => (
          <OttBadge
            key={code}
            letter={ottBadgeLetter(code)}
            name={ottNames[code] ?? code}
            size={20}
          />
        ))}
      </span>
    </button>
  );
}
