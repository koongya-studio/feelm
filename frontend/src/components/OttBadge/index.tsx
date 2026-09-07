/*
 * OTT 식별 배지.
 * 와이어프레임은 OTT를 서비스명 첫 글자 한 자로 표시한다. 실제 로고를 쓰지 않는다.
 *
 * 크기가 화면마다 다르므로 size로 받는다. 와이어프레임 실측값이다.
 * 1.2 초기 설정 38px · 2.3 카드 메뉴 28px · 2.4 영화 상세 26px
 * 2.1 · 2.2 · 3.1-A 22px · 2.1 목록 20px
 *
 * 색은 서비스 브랜드 색이 아니다. 와이어프레임은 무채색이며 두 가지만 쓴다.
 * default  : 배경 #A5A29B, 어두운 글자 — 대부분의 화면
 * selected : 배경 #3C3A36, 흰 글자 — 1.2에서 구독 중으로 선택된 OTT
 */
import type { CSSProperties } from 'react';
import styles from './OttBadge.module.css';

type Props = {
  /** 표시할 한 글자. 예: 넷플릭스 N */
  letter: string;
  /** OTT 이름. 보조기술에 읽히는 이름이 된다 */
  name: string;
  /** 한 변의 길이(px) */
  size?: number;
  variant?: 'default' | 'selected';
};

export default function OttBadge({ letter, name, size = 22, variant = 'default' }: Props) {
  return (
    <span
      className={styles.badge}
      data-variant={variant}
      style={
        {
          '--ott-badge-size': `${size}px`,
          '--ott-badge-font-size': `${Math.round(size * 0.5 * 10) / 10}px`,
        } as CSSProperties
      }
      role="img"
      aria-label={name}
    >
      {letter}
    </span>
  );
}
