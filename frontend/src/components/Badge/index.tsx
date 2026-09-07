/*
 * 상태 · 수치 배지.
 * 와이어프레임 2.2 추천 카드의 `일치 92% · 안 봄`, 2.1의 `취향 일치 92%` 형태다.
 *
 * 두 화면의 크기가 다르므로 size로 받는다. 와이어프레임 실측값이다.
 * sm : padding 3px 8px  · 9.5px/700  (2.2 · 2.3-A 카드 위 배지)
 * md : padding 4px 10px · 11.5px/700 (2.1 오늘의 추천 대표 카드)
 *
 * 조작할 수 없는 표시 요소다. 취향 키워드와 태그는 Chip을 쓴다.
 */
import type { ReactNode } from 'react';
import styles from './Badge.module.css';

type Props = {
  children: ReactNode;
  size?: 'sm' | 'md';
};

export default function Badge({ children, size = 'sm' }: Props) {
  return (
    <span className={styles.badge} data-size={size}>
      {children}
    </span>
  );
}
