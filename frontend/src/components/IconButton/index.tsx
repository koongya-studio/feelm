/*
 * 아이콘만 있는 버튼.
 * 화면에서 사용하는 대표 형태는 다음과 같다.
 *
 * | 화면 | 크기 | 모양 | 배경 |
 * | 2.1 알림 | 36px | 원형 | 없음, 테두리 1px |
 * | 2.2 모바일 관심없음 | 26px | 둥근 사각 | 반투명 흰색, 테두리 1px |
 * | 2.2 데스크톱 관심없음 | 26px · 36px | 둥근 사각 | 없음 |
 *
 * 보이는 크기는 와이어프레임을 따르고 터치 영역만 44px로 넓힌다
 * (common-components.md 6.3). label은 필수이며 접근 가능한 이름이 된다.
 */
import type { CSSProperties, ButtonHTMLAttributes, ReactNode } from 'react';
import VisuallyHidden from '../VisuallyHidden';
import styles from './IconButton.module.css';

type Props = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> & {
  /** 접근 가능한 이름. 화면에는 보이지 않는다 */
  label: string;
  icon: ReactNode;
  /** 보이는 지름 또는 한 변의 길이(px) */
  size?: number;
  shape?: 'circle' | 'rounded';
  /**
   * `plain`은 아이콘만 남는다 — 테두리 · 면 · 그림자가 없다(업무 -783). 2026-09-21 시안의
   * 카드 위 버튼이 그 모양이며, 카드가 이미 흰 면이라 버튼까지 면을 가지면 면 위에 면이 된다.
   */
  variant?: 'outline' | 'translucent' | 'filled' | 'plain';
};

export default function IconButton({
  label,
  icon,
  size = 36,
  shape = 'circle',
  variant = 'outline',
  ...rest
}: Props) {
  return (
    <button
      type="button"
      className={styles.button}
      data-shape={shape}
      data-variant={variant}
      style={{ '--icon-button-size': `${size}px` } as CSSProperties}
      {...rest}
    >
      <span className={styles.box}>{icon}</span>
      <VisuallyHidden>{label}</VisuallyHidden>
    </button>
  );
}
