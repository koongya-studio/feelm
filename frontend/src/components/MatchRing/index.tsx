/*
 * 취향 일치율 원형 링. 7.2-C가 쓴다.
 *
 * 와이어프레임 7.2-C는 `76%`를 큰 글자와 **가로 진행 바**로 그렸다. 레퍼런스 지시가
 * 원형 그래프 또는 애니메이션 링을 요구해 원형으로 바꿨다. 항목별 비교(장르 · 국가 ·
 * 시대 · 감독)가 이미 가로 바 4줄이라, 전체 일치율까지 가로 바면 다섯 줄이 같은 모양이
 * 되어 무엇이 대표 수치인지 보이지 않는다.
 *
 * 값은 0에서 결과값까지 차오른다(레퍼런스 지시 7). `prefers-reduced-motion`에서는
 * 토큰이 전환 시간을 0으로 만들어 처음부터 결과값으로 그려진다.
 *
 * 접근성: 링은 장식이고 수치는 가운데 글자가 읽어 준다. `role="img"`에 값을 담아
 * 화면 낭독기가 `취향 일치율 76퍼센트`로 읽게 한다.
 */
import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import styles from './MatchRing.module.css';

type Props = {
  /** 일치율(%). 0~100 */
  rate: number;
  size?: number;
};

const DEFAULT_SIZE = 132;
/** 선 두께. 지름 대비 비율로 두면 크기를 바꿔도 굵기가 유지된다 */
const STROKE_RATIO = 0.075;

export default function MatchRing({ rate, size = DEFAULT_SIZE }: Props) {
  /*
   * 첫 프레임은 0으로 그리고 그 다음에 결과값으로 바꾼다. 처음부터 결과값으로 두면
   * 전환할 대상이 없어 차오르지 않는다.
   */
  const [drawn, setDrawn] = useState(0);

  useEffect(() => {
    const frame = requestAnimationFrame(() => setDrawn(rate));

    return () => cancelAnimationFrame(frame);
  }, [rate]);

  const stroke = size * STROKE_RATIO;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;

  const style = {
    '--ring-size': `${size}px`,
    // 남길 길이. 값이 클수록 적게 그려진다
    '--ring-offset': `${circumference * (1 - drawn / 100)}`,
    '--ring-length': `${circumference}`,
  } as CSSProperties;

  return (
    <div className={styles.ring} style={style} role="img" aria-label={`취향 일치율 ${rate}퍼센트`}>
      <svg viewBox={`0 0 ${size} ${size}`} aria-hidden="true" focusable="false">
        <circle
          className={styles.track}
          cx={size / 2}
          cy={size / 2}
          r={radius}
          strokeWidth={stroke}
        />
        {/* 12시에서 시작하도록 -90도 돌린다 */}
        <circle
          className={styles.value}
          cx={size / 2}
          cy={size / 2}
          r={radius}
          strokeWidth={stroke}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </svg>

      <span className={styles.number} aria-hidden="true">
        {rate}
        <span className={styles.unit}>%</span>
      </span>
    </div>
  );
}
