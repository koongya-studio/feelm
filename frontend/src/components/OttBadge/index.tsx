/*
 * OTT 식별 배지.
 * 와이어프레임은 OTT를 서비스명 첫 글자 한 자로 표시한다. 실제 로고를 쓰지 않는다.
 *
 * 크기가 화면마다 다르므로 size로 받는다. 와이어프레임 실측값이다.
 * 1.2 초기 설정 38px · 2.3 카드 메뉴 28px · 2.4 영화 상세 26px
 * 2.1 · 2.2 · 3.1-A 22px · 2.1 목록 20px
 *
 * 글자 크기는 기본적으로 한 변의 절반이다. 1.2만 38px 상자에 16px 글자여서 비율이
 * 다르므로 fontSize로 받는다. 모서리 반경도 1.2는 10px, 나머지는 8px이다.
 *
 * 색은 서비스 브랜드 색이 아니다. 와이어프레임은 무채색이며 세 가지를 쓴다.
 * default    : 배경 #A5A29B, 어두운 글자 — 2.1 · 2.2 · 2.3 카드
 * unselected : 배경 #A5A29B, 흰 글자   — 1.2에서 선택하지 않은 OTT
 * selected   : 배경 #3C3A36, 흰 글자   — 1.2에서 구독 중으로 선택된 OTT
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
  /** 글자 크기(px). 생략하면 한 변의 절반 */
  fontSize?: number;
  /** 모서리 반경(px) */
  radius?: number;
  variant?: 'default' | 'unselected' | 'selected';
  /**
   * 보조기술에 이름을 읽힐지. 이미 같은 이름이 옆에 텍스트로 있으면 false로 두어
   * 두 번 읽히지 않게 한다.
   */
  labelled?: boolean;
};

export default function OttBadge({
  letter,
  name,
  size = 22,
  fontSize,
  radius,
  variant = 'default',
  labelled = true,
}: Props) {
  const style = {
    '--ott-badge-size': `${size}px`,
    '--ott-badge-font-size': `${fontSize ?? Math.round(size * 0.5 * 10) / 10}px`,
    '--ott-badge-radius': radius === undefined ? 'var(--radius-icon)' : `${radius}px`,
  } as CSSProperties;

  if (!labelled) {
    return (
      <span className={styles.badge} data-variant={variant} style={style} aria-hidden="true">
        {letter}
      </span>
    );
  }

  return (
    <span
      className={styles.badge}
      data-variant={variant}
      style={style}
      role="img"
      aria-label={name}
    >
      {letter}
    </span>
  );
}
