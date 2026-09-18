/*
 * 버튼.
 * 와이어프레임에서 확인된 세 형태에 시안이 더한 하나를 변형으로 둔다.
 *
 * primary   : 높이 52px, 배경 #3C3A36, 흰 글자   (1.2 선택 완료, 2.2-A 다시 시도, 2.5 평가 완료)
 * secondary : 높이 52px, 테두리 1px, 어두운 글자 (2.2-A 영화 검색하기, 2.4-A 아직 못 봤어요)
 * text      : 높이 없음, 작은 글자, 옅은 색       (1.1 차차 찾아갈래요, 2.5 나중에 평가하기)
 *
 * primary와 secondary는 와이어프레임에서 화면 폭을 채우는 형태로만 쓰인다.
 *
 * brand     : primary와 같은 알약에 로고 빨강 면 (3.1 필름 늘리기, 2.4 별점 남기기,
 *             5.1 ~ 5.8 파티 화면의 주 버튼)
 *   사용자 시안 2026-09-17(업무 -458 · -462). **시안이 있는 화면 전용이다** — 시안이
 *   주 버튼을 빨강 면으로 그린 화면만 쓰고, 나머지 화면은 검정 그대로다. 앱 전체를
 *   바꾸는 것은 시안이 없는 화면까지 색을 바꾸는 일이라 하지 않는다.
 */
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import styles from './Button.module.css';

type Props = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'text' | 'brand';
  children: ReactNode;
};

export default function Button({ variant = 'primary', children, ...rest }: Props) {
  return (
    <button type="button" className={styles.button} data-variant={variant} {...rest}>
      {children}
    </button>
  );
}
