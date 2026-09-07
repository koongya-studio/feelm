/*
 * 별점 표시 (읽기 전용).
 *
 * 점수는 0.5점 단위 0.5~5.0이다 (팀 합의, 2026-09-07).
 * 와이어프레임 2.5의 `4.5 / 5.0 · 0.5점 단위` 표기를 따른다.
 * 계약도 같은 규칙이다 (FR-19, API-036 `multipleOf: 0.5`, `erd.md` CHECK).
 *
 * 별 모양과 반쪽 별 그라디언트는 와이어프레임의 SVG를 그대로 쓴다.
 * 채운 별 #3C3A36, 빈 별은 흰 배경에 #8A8781 테두리.
 *
 * score는 원본 값을 그대로 받는다. 별 렌더링은 0.5 단위로 맞추되
 * 숫자 표시와 보조기술 읽기는 원본 값을 쓴다. 2.4의 평균 별점처럼
 * 0.5 단위가 아닌 값(3.7)도 표시할 수 있어야 하기 때문이다.
 *
 * 숫자 값을 보조기술이 읽을 수 있게 함께 제공한다(common-components.md 6.5).
 */
import { useId } from 'react';
import VisuallyHidden from '../VisuallyHidden';
import type { CSSProperties } from 'react';
import styles from './RatingStars.module.css';

const STAR_PATH =
  'M10 1.5l2.6 5.3 5.9.9-4.2 4.1 1 5.8L10 14.9l-5.3 2.7 1-5.8L1.5 7.7l5.9-.9L10 1.5z';
const MAX_SCORE = 5;

type Fill = 'full' | 'half' | 'empty';

/** 별 하나하나의 채움 상태를 0.5 단위로 계산한다 */
function toFills(score: number): Fill[] {
  const clamped = Math.max(0, Math.min(MAX_SCORE, score));
  const stepped = Math.round(clamped * 2) / 2;
  const full = Math.floor(stepped);
  const hasHalf = stepped - full >= 0.5;

  return Array.from({ length: MAX_SCORE }, (_, i) => {
    if (i < full) return 'full';
    if (i === full && hasHalf) return 'half';
    return 'empty';
  });
}

type Props = {
  /** 0.5점 단위 0.5~5.0. 평균 별점처럼 0.5 단위가 아닌 값도 받는다 */
  score: number;
  /** 별 하나의 크기(px) */
  size?: number;
  /** 별 사이 간격(px). 와이어프레임 2.4는 3px, 2.5 입력은 10px */
  gap?: number;
  /** 숫자 값을 화면에도 표시할지 */
  showValue?: boolean;
};

export default function RatingStars({ score, size = 17, gap = 1, showValue = false }: Props) {
  const gradientId = useId();
  const fills = toFills(score);
  const label = score.toFixed(1);

  return (
    <span className={styles.wrapper}>
      <span
        className={styles.stars}
        style={{ '--rating-stars-gap': `${gap}px` } as CSSProperties}
        aria-hidden="true"
      >
        {fills.includes('half') ? (
          <svg width="0" height="0" className={styles.defs} focusable="false">
            <defs>
              <linearGradient id={gradientId}>
                <stop offset="50%" stopColor="var(--color-nav-active)" />
                <stop offset="50%" stopColor="var(--color-bg)" />
              </linearGradient>
            </defs>
          </svg>
        ) : null}

        {fills.map((fill, i) => (
          <svg key={i} width={size} height={size} viewBox="0 0 20 20" focusable="false">
            <path
              d={STAR_PATH}
              fill={
                fill === 'full'
                  ? 'var(--color-nav-active)'
                  : fill === 'half'
                    ? `url(#${gradientId})`
                    : 'var(--color-bg)'
              }
              stroke={fill === 'full' ? 'none' : 'var(--color-star-empty-line)'}
              strokeWidth={fill === 'full' ? undefined : 1.3}
              // 별 꼭짓점의 기본 miter join이 뾰족하게 뻗어 외곽선이 겹쳐 보인다
              strokeLinejoin="round"
            />
          </svg>
        ))}
      </span>

      {showValue ? (
        <span className={styles.value}>{label}</span>
      ) : (
        <VisuallyHidden>{`별점 ${label}점 / ${MAX_SCORE}점`}</VisuallyHidden>
      )}
    </span>
  );
}
