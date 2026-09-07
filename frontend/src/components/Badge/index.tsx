/*
 * 상태 · 수치 배지.
 * 와이어프레임 2.2 추천 카드의 `일치 92% · 안 봄`, 2.1의 `아직 안 본 영화` 형태다.
 * padding 3px 8px, 완전 둥근 모서리, 배경 #3C3A36, 흰 글자, 9.5px/700
 *
 * 조작할 수 없는 표시 요소다. 취향 키워드와 태그는 Chip을 쓴다.
 */
import type { ReactNode } from 'react';
import styles from './Badge.module.css';

export default function Badge({ children }: { children: ReactNode }) {
  return <span className={styles.badge}>{children}</span>;
}
