/*
 * 별점 입력.
 * 와이어프레임 2.5의 별 5개(40px, 간격 10px)와 `4.5 / 5.0 · 0.5점 단위` 표기다.
 *
 * 계약은 0.5~5.0의 0.5 배수만 받고 4.49 같은 값을 반올림하지 않고 거절한다
 * (API-036 `multipleOf: 0.5`). 그래서 화면에서 0.5 배수만 만든다.
 *
 * 별 위에서는 각 별의 좌우 절반을 누르거나 드래그해 0.5점 단위로 고른다.
 * 아래 네이티브 range는 드래그 · 탭 · 키보드(화살표 · Home · End)를 담당하며
 * 현재 값이 보조기술에 읽힌다(common-components.md 6.5).
 *
 * `0`은 아직 고르지 않은 상태를 뜻하는 화면 전용 값이다. 계약의 최소값은 0.5이므로
 * 0은 서버로 보내지 않는다.
 */
import { useRef } from 'react';
import type { CSSProperties, PointerEvent } from 'react';
import RatingStars from '../RatingStars';
import styles from './RatingInput.module.css';

const MAX_SCORE = 5;
const STEP = 0.5;
const STAR_SIZE = 40;
const STAR_GAP = 10;

type Props = {
  /** 0은 선택 안 함. 그 밖에는 0.5~5.0의 0.5 배수 */
  value: number;
  onChange: (value: number) => void;
  disabled?: boolean;
};

export default function RatingInput({ value, onChange, disabled = false }: Props) {
  const activePointer = useRef<number | null>(null);
  // 트랙에서 손잡이 왼쪽을 채우는 비율. CSS가 그라디언트 경계로 쓴다
  const fill = { '--rating-fill': `${(value / MAX_SCORE) * 100}%` } as CSSProperties;

  function updateFromStar(event: PointerEvent<HTMLSpanElement>) {
    const x = event.clientX - event.currentTarget.getBoundingClientRect().left;
    const slot = Math.max(0, Math.min(MAX_SCORE - 1, Math.floor(x / (STAR_SIZE + STAR_GAP))));
    const withinSlot = x - slot * (STAR_SIZE + STAR_GAP);
    onChange(slot + (withinSlot < STAR_SIZE / 2 ? STEP : 1));
  }

  function handlePointerDown(event: PointerEvent<HTMLSpanElement>) {
    if (disabled || (event.pointerType === 'mouse' && event.button !== 0)) return;
    activePointer.current = event.pointerId;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    updateFromStar(event);
  }

  function handlePointerMove(event: PointerEvent<HTMLSpanElement>) {
    if (!disabled && activePointer.current === event.pointerId) updateFromStar(event);
  }

  function handlePointerEnd(event: PointerEvent<HTMLSpanElement>) {
    if (activePointer.current !== event.pointerId) return;
    activePointer.current = null;
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  return (
    <div className={styles.rating}>
      <span
        className={styles.visual}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerEnd}
        onPointerCancel={handlePointerEnd}
      >
        <RatingStars score={value} size={STAR_SIZE} gap={STAR_GAP} />
      </span>

      <input
        type="range"
        className={styles.slider}
        style={fill}
        min={0}
        max={MAX_SCORE}
        step={STEP}
        value={value}
        disabled={disabled}
        aria-label="별점"
        aria-valuetext={value === 0 ? '선택 안 함' : `${value.toFixed(1)}점 / ${MAX_SCORE}점`}
        onChange={(event) => onChange(Number(event.target.value))}
      />

      <p className={styles.value}>
        {value === 0
          ? `별점을 선택해 주세요 · ${STEP}점 단위`
          : `${value.toFixed(1)} / 5.0 · ${STEP}점 단위`}
      </p>
    </div>
  );
}
