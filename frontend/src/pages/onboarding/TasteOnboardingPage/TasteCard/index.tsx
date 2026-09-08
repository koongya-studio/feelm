/*
 * 1.1 영화 취향 초기 설정 — 배치 카드
 *
 * 실측값: 58x82 · radius 8
 * 미선택   : 테두리 1px #C7C4BE (Poster 기본값)
 * 가까이    : 테두리 1px #3C3A36
 * 멀리      : 테두리 1px #C7C4BE + opacity 0.55 + grayscale(60%)
 * 안 봤어요 : 와이어프레임에 별도 표현이 없어 미선택과 같은 모습으로 둔다. 상태는
 *             접근 가능한 이름으로 전달한다. (`[?]` 표시 방법 미확정)
 *
 * `⊖`는 top -8 right -8, 22px 원, 테두리 1.5px, 안쪽 막대 10x2.
 * 보이는 크기는 그대로 두고 터치 영역만 넓힌다(common-components.md 6.3).
 *
 * 카드는 button이므로 키보드로도 선택할 수 있다. 드래그는 포인터 조작이고, 눌렀다가
 * 거의 움직이지 않으면 탭으로 보아 선택 버튼을 연다.
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
  onPointerDown: (event: PointerEvent<HTMLButtonElement>) => void;
  onClick: () => void;
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
  onPointerDown,
  onClick,
  onRemove,
}: Props) {
  return (
    <div
      className={styles.card}
      style={{ '--card-x': `${x}px`, '--card-y': `${y}px` } as CSSProperties}
      data-value={value}
      data-dragging={dragging}
    >
      <button
        type="button"
        className={styles.surface}
        onPointerDown={onPointerDown}
        onClick={onClick}
        aria-label={`${title}, ${tasteValueLabel(value)}`}
      >
        <Poster
          width={58}
          height={82}
          radius={8}
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
