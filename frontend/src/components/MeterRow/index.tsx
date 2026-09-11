/*
 * 라벨 · 가로 바 · 수치 한 줄. 7.2-C 항목별 비교(장르 · 국가 · 시대 · 감독)가 쓴다.
 *
 * 와이어프레임 7.2-C의 사양을 그대로 옮겼다. 라벨 44px · 바 h6 · 수치 32px.
 * 다만 한국어 라벨이 두 글자로 고정이 아니므로 폭을 고정하지 않고 최소폭만 준다.
 *
 * 바는 0에서 결과값까지 차오른다(레퍼런스 지시 7). 여러 줄이 나란히 있을 때
 * `delayIndex`로 조금씩 늦춰 순차적으로 차오르게 한다.
 *
 * 접근성: `progressbar`로 두고 값을 넘긴다. 오른쪽 수치 글자는 장식이므로 숨긴다.
 */
import type { CSSProperties } from 'react';
import styles from './MeterRow.module.css';

type Props = {
  label: string;
  /** 0~100 */
  rate: number;
  /** 몇 번째 줄인지. 순차 등장 지연에 쓴다 */
  delayIndex?: number;
};

/** 줄당 지연(ms). 4줄이면 마지막이 180ms 늦는다 */
const STEP_DELAY_MS = 60;

export default function MeterRow({ label, rate, delayIndex = 0 }: Props) {
  const style = {
    '--meter-rate': `${rate}%`,
    '--meter-delay': `${delayIndex * STEP_DELAY_MS}ms`,
  } as CSSProperties;

  return (
    <div className={styles.row} style={style}>
      <span className={styles.label}>{label}</span>

      <span
        className={styles.track}
        role="progressbar"
        aria-label={label}
        aria-valuenow={rate}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <span className={styles.fill} />
      </span>

      <span className={styles.value} aria-hidden="true">
        {rate}%
      </span>
    </div>
  );
}
