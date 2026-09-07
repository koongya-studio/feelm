/*
 * 버튼.
 * 와이어프레임에서 확인된 세 형태를 변형으로 둔다.
 *
 * primary   : 높이 52px, 배경 #3C3A36, 흰 글자   (1.2 선택 완료, 2.2-A 다시 시도, 2.5 평가 완료)
 * secondary : 높이 52px, 테두리 1px, 어두운 글자 (2.2-A 영화 검색하기, 2.4-A 아직 못 봤어요)
 * text      : 높이 없음, 작은 글자, 옅은 색       (1.1 차차 찾아갈래요, 2.5 나중에 평가하기)
 *
 * primary와 secondary는 와이어프레임에서 화면 폭을 채우는 형태로만 쓰인다.
 */
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import styles from './Button.module.css';

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'text';
  children: ReactNode;
};

export default function Button({ variant = 'primary', children, ...rest }: Props) {
  return (
    <button type="button" className={styles.button} data-variant={variant} {...rest}>
      {children}
    </button>
  );
}
