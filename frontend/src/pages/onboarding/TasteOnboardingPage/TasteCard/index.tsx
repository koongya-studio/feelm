/*
 * 1.1 취향 카드 한 장.
 *
 * 포스터는 **버튼**이다(업무 -610). 드래그(포인터)로 원 안팎에 놓는 것이 기본 조작이고,
 * 키보드로도 같은 결과를 낼 수 있어야 한다.
 * - Enter · Space: 원 안(LIKE) ↔ 원 밖(DISLIKE)을 오간다
 * - 방향키: 카드를 조금씩 옮긴다. 경계를 넘으면 드래그와 같이 판정이 바뀐다
 * - `aria-pressed`가 LIKE 여부를, 이름이 `제목, 가까이/멀리`를 읽어 준다
 *
 * 포인터 탭은 그대로 아무 일도 하지 않는다 — 카드를 집는 동작과 겹치기 때문이다. 키보드로
 * 누른 클릭만 `event.detail === 0`으로 가려 토글한다.
 */
import Poster from '../../../../components/Poster';
import VisuallyHidden from '../../../../components/VisuallyHidden';
import styles from './TasteCard.module.css';
import type { CSSProperties, KeyboardEvent, MouseEvent, PointerEvent } from 'react';
import type { TasteValue } from '../tasteValue';
import { tasteValueLabel } from '../tasteValue';

type Props = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  value: TasteValue;
  /** 카드 크기(px). 배치 영역이 작으면 화면이 줄여서 준다(업무 -805) */
  width: number;
  height: number;
  x: number;
  y: number;
  dragging: boolean;
  front: boolean;
  onPointerDown: (event: PointerEvent<HTMLButtonElement>) => void;
  /** 키보드로 원 안팎을 오간다 */
  onToggle: () => void;
  /** 방향키로 옮긴다. 단위는 px */
  onNudge: (dx: number, dy: number) => void;
  onRemove: () => void;
};

/** 방향키 한 번에 옮기는 거리(px). 카드 폭의 1/4쯤이라 몇 번이면 경계를 넘는다 */
const NUDGE_STEP = 24;

const ARROWS: Record<string, [number, number]> = {
  ArrowLeft: [-NUDGE_STEP, 0],
  ArrowRight: [NUDGE_STEP, 0],
  ArrowUp: [0, -NUDGE_STEP],
  ArrowDown: [0, NUDGE_STEP],
};

export default function TasteCard({
  movieId,
  title,
  posterUrl,
  value,
  width,
  height,
  x,
  y,
  dragging,
  front,
  onPointerDown,
  onToggle,
  onNudge,
  onRemove,
}: Props) {
  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    // 포인터 클릭은 `detail`이 1 이상이다. 키보드(Enter · Space)만 토글한다
    if (event.detail === 0) {
      onToggle();
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    const delta = ARROWS[event.key];

    if (delta) {
      event.preventDefault();
      onNudge(delta[0], delta[1]);
    }
  }

  return (
    <div
      className={styles.card}
      style={
        {
          '--card-x': `${x}px`,
          '--card-y': `${y}px`,
          '--card-width': `${width}px`,
          '--card-height': `${height}px`,
        } as CSSProperties
      }
      data-value={value}
      data-dragging={dragging}
      data-front={front}
    >
      <button
        type="button"
        className={styles.surface}
        onPointerDown={onPointerDown}
        onDragStart={(event) => event.preventDefault()}
        onClick={handleClick}
        onKeyDown={handleKeyDown}
        aria-pressed={value === 'LIKE'}
        aria-label={`${title}, ${tasteValueLabel(value)}`}
      >
        <Poster
          width={width}
          height={height}
          radius={11}
          src={posterUrl ?? undefined}
          title=""
          seed={movieId}
        />
      </button>

      <button type="button" className={styles.remove} onClick={onRemove}>
        <span className={styles.removeBar} aria-hidden="true" />
        <VisuallyHidden>{title} 목록에서 제거</VisuallyHidden>
      </button>
    </div>
  );
}
