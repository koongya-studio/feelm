/*
 * 별점 표시 (읽기 전용).
 * 점수는 정수 1~5다. 와이어프레임은 0.5점 단위 반쪽 별을 쓰지만
 * Screen Spec 8.1에서 계약 기준(FR-19, API-036 score integer 1~5)으로 확정했다.
 * 반쪽 별은 구현하지 않는다.
 *
 * 별 모양은 와이어프레임의 SVG path를 그대로 쓴다.
 * 채운 별은 #3C3A36, 빈 별은 흰 배경에 #8A8781 테두리다.
 * 빈 별 사양은 와이어프레임 반쪽 별의 stroke 값에서 가져왔다.
 *
 * 숫자 값을 보조기술이 읽을 수 있게 함께 제공한다(common-components.md 6.5).
 */
import VisuallyHidden from '../VisuallyHidden';
import styles from './RatingStars.module.css';

const STAR_PATH =
  'M10 1.5l2.6 5.3 5.9.9-4.2 4.1 1 5.8L10 14.9l-5.3 2.7 1-5.8L1.5 7.7l5.9-.9L10 1.5z';
const MAX_SCORE = 5;

type Props = {
  /** 정수 1~5 */
  score: number;
  /** 별 하나의 크기(px) */
  size?: number;
  /** 숫자 값을 화면에도 표시할지 */
  showValue?: boolean;
};

export default function RatingStars({ score, size = 17, showValue = false }: Props) {
  const filled = Math.max(0, Math.min(MAX_SCORE, Math.round(score)));

  return (
    <span className={styles.wrapper}>
      <span className={styles.stars} aria-hidden="true">
        {Array.from({ length: MAX_SCORE }, (_, i) => (
          <svg key={i} width={size} height={size} viewBox="0 0 20 20" focusable="false">
            <path
              d={STAR_PATH}
              fill={i < filled ? 'var(--color-nav-active)' : 'var(--color-bg)'}
              stroke={i < filled ? 'none' : 'var(--color-star-empty-line)'}
              strokeWidth={i < filled ? undefined : 1.3}
            />
          </svg>
        ))}
      </span>
      {showValue ? (
        <span className={styles.value}>{filled}</span>
      ) : (
        <VisuallyHidden>{`별점 ${filled}점 / ${MAX_SCORE}점`}</VisuallyHidden>
      )}
    </span>
  );
}
