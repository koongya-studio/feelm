/*
 * 화면에 보이지 않고 보조기술에만 읽히는 텍스트.
 * 아이콘만 있는 버튼의 이름, 별점의 숫자 값 등에 사용한다.
 */
import type { ReactNode } from 'react';
import styles from './VisuallyHidden.module.css';

export default function VisuallyHidden({ children }: { children: ReactNode }) {
  return <span className={styles.hidden}>{children}</span>;
}
