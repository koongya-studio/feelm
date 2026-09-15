/*
 * 아이콘만 있는 버튼.
 * 와이어프레임에서 확인된 형태는 세 가지다.
 *
 * | 화면 | 크기 | 모양 | 배경 |
 * | 2.1 알림 | 36px | 원형 | 없음, 테두리 1px |
 * | 2.2 카드 ⋯ | 26px | 둥근 사각 | 반투명 흰색, 테두리 1px |
 * | 2.3 카드 ⋯ (열림) | 28px | 둥근 사각 | #3C3A36, 흰 아이콘 |
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
  variant?: 'outline' | 'translucent' | 'filled';
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
