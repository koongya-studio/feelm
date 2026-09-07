/*
 * 취향 키워드 · 태그.
 * 와이어프레임 3.1 취향 키워드와 5.9 태그에서 같은 사양으로 확인된다.
 * padding 3px 10px, 완전 둥근 모서리, 테두리 1px, 11px/600
 *
 * 이번 범위에서는 조작할 수 없다. 나중에 필터로 쓰이면 선택 상태를 추가한다.
 */
import type { ReactNode } from 'react';
import styles from './Chip.module.css';

export default function Chip({ children }: { children: ReactNode }) {
  return <span className={styles.chip}>{children}</span>;
}
