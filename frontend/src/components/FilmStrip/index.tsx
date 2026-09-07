/*
 * 프레임을 필름 모양으로 표시하는 컨테이너.
 *
 * 와이어프레임 1.3은 이 자리에 이미지 에셋을 넣어 두었고, 필름 시각 표현은
 * `DESIGN_GOALS.md`의 Film motif가 미확정이다(screen-spec.md 8.4). 와이어프레임이
 * 직접 CSS로 그린 필름 표현은 2.2 · 2.3의 스트립 하나뿐이므로 그 사양을 그대로 쓴다.
 *
 * 실측값(2.2 · 2.3): 배경 #E9E7E1 · 양쪽 구멍 폭 12px, 가장자리에서 5px
 * 구멍 무늬 repeating-linear-gradient(180deg, #FFFFFF 0 8px, transparent 8px 21px)
 *
 * 방향은 세로만 지원한다. 가로 스트립이 필요한 화면(3.1)에서 필요해지면 그때 넓힌다.
 * 색상과 무늬는 디자인 확정 후 이 파일에서 교체한다.
 *
 * 2.2 오늘의 추천이 같은 표현을 화면 안에 직접 가지고 있다. 승격 기준(두 번째 화면이
 * 필요해질 때)에 맞으므로 2.2를 이 컴포넌트로 바꾸는 것이 맞지만, 이미 병합된 다른
 * 업무의 화면이라 이 업무에서 손대지 않았다. 별도로 정리한다.
 */
import type { CSSProperties, ReactNode } from 'react';
import styles from './FilmStrip.module.css';

type Props = {
  /** 스트립 폭(px). 와이어프레임 2.2는 224px */
  width?: number;
  children?: ReactNode;
};

export default function FilmStrip({ width = 224, children }: Props) {
  return (
    <div
      className={styles.strip}
      style={{ '--film-strip-width': `${width}px` } as CSSProperties}
      data-testid="film-strip"
    >
      <span className={styles.perforation} data-side="left" aria-hidden="true" />
      <span className={styles.perforation} data-side="right" aria-hidden="true" />
      {children}
    </div>
  );
}
