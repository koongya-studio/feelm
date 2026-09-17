/*
 * 1.1 영화 취향 초기 설정 — 배치 카드
 *
 * 크기 88x124 · radius 11은 확정 영화의 포스터를 식별할 수 있도록 키운 값이고 부모의
 * `CARD_WIDTH`·`CARD_HEIGHT`와 같아야 한다.
 *
 * 상태는 위치 자체로 읽는다. 처음 받은 카드는 원 밖의 흐린 DISLIKE로 시작하고,
 * 점선 안의 LIKE는 선명하게 보인다. 가까이 카드에 별도 빨강 테두리를 두지 않는다.
 *
 * 이전 주석은 폐기된 이전 방향의 hex(`#C7C4BE` · `#3C3A36`)를 적고 있었다.
 *
 * `⊖`는 top -8 right -8, 22px 원, 테두리 1.5px, 안쪽 막대 10x2.
 * 보이는 크기는 그대로 두고 터치 영역만 넓힌다(common-components.md 6.3).
 *
 * 카드 표면은 영화 제목과 판정 상태를 읽어 주는 이미지 역할이며, 선택 동작은 드래그
 * 하나만 제공한다. 보지 않은 영화는 별도의 `⊖` 버튼으로 제외한다.
 */
import Poster from '../../../../components/Poster';
import VisuallyHidden from '../../../../components/VisuallyHidden';
import styles from './TasteCard.module.css';
import type { CSSProperties, PointerEvent } from 'react';
import type { TasteValue } from '../tasteValue';
import { tasteValueLabel } from '../tasteValue';

type Props = {
  /** 자리표시자 색조를 고르는 값 (Poster) */
  movieId: number;
  title: string;
  posterUrl: string | null;
  value: TasteValue;
  x: number;
  y: number;
  dragging: boolean;
  front: boolean;
  onPointerDown: (event: PointerEvent<HTMLDivElement>) => void;
  onRemove: () => void;
};

export default function TasteCard({
  movieId,
  title,
  posterUrl,
  value,
  x,
  y,
  dragging,
  front,
  onPointerDown,
  onRemove,
}: Props) {
  return (
    <div
      className={styles.card}
      style={{ '--card-x': `${x}px`, '--card-y': `${y}px` } as CSSProperties}
      data-value={value}
      data-dragging={dragging}
      data-front={front}
    >
      <div
        className={styles.surface}
        onPointerDown={onPointerDown}
        onDragStart={(event) => event.preventDefault()}
        role="img"
        aria-label={`${title}, ${tasteValueLabel(value)}`}
      >
        <Poster
          width={88}
          height={124}
          radius={11}
          src={posterUrl ?? undefined}
          title=""
          seed={movieId}
        />
      </div>

      <button type="button" className={styles.remove} onClick={onRemove}>
        <span className={styles.removeBar} aria-hidden="true" />
        <VisuallyHidden>{title} 목록에서 제거</VisuallyHidden>
      </button>
    </div>
  );
}
