/*
 * 취향 키워드 · 태그.
 * 와이어프레임 3.1 취향 키워드와 5.9 태그에서 같은 사양으로 확인된다.
 * padding 3px 10px, 완전 둥근 모서리, 테두리 1px, 11px/600
 *
 * 이번 범위에서는 조작할 수 없다. 나중에 필터로 쓰이면 선택 상태를 추가한다.
 *
 * 업무 -305에서 `variant`가 붙었다. 와이어프레임 7.2-A · 7.2-B · 7.2-C가 칩을
 * **채운 것과 테두리만 있는 것 두 종류로** 그린다. 앞쪽 몇 개를 채워 대표 키워드를
 * 구분하는 용법이며, 레퍼런스 4장도 모두 같은 방식을 쓴다.
 */
import type { ReactNode } from 'react';
import styles from './Chip.module.css';

type Props = {
  /**
   * `outline` 테두리만 (기본값. 기존 화면이 쓰던 형태다)
   * `solid`   채운 것. 대표 키워드를 앞에 둘 때 쓴다
   */
  variant?: 'outline' | 'solid';
  children: ReactNode;
};

export default function Chip({ variant = 'outline', children }: Props) {
  return (
    <span className={styles.chip} data-variant={variant}>
      {children}
    </span>
  );
}
