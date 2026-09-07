/*
 * 영화 포스터.
 * 와이어프레임은 포스터 자리를 대각선 두 줄이 교차하는 자리표시자로 표현한다.
 * 테두리 1px #C7C4BE, 대각선 #D5D2CC, 배경 흰색.
 *
 * 크기와 모서리 반경은 화면마다 다르므로 props로 받는다. 와이어프레임 실측값이다.
 * 2.1 대표 104x148 r8 · 2.2 카드 138x196 r3 · 3.1 프레임 118x166 r4
 * 1.1 온보딩 카드 58x82 r8 · 8.1 알림 44x62 r6
 *
 * src가 없으면 자리표시자를 표시한다. 이번 단계는 목업 데이터로 동작하며
 * 실제 포스터 이미지 권리는 확정되지 않았다(PRODUCT.md).
 */
import type { CSSProperties } from 'react';
import styles from './Poster.module.css';

type Props = {
  width: number;
  height: number;
  radius?: number;
  /** 포스터 이미지 주소. 없으면 자리표시자 */
  src?: string;
  /** 영화 제목. 대체 텍스트가 된다 */
  title: string;
};

export default function Poster({ width, height, radius = 8, src, title }: Props) {
  const style = {
    '--poster-width': `${width}px`,
    '--poster-height': `${height}px`,
    '--poster-radius': `${radius}px`,
  } as CSSProperties;

  if (src) {
    return <img className={styles.poster} style={style} src={src} alt={title} loading="lazy" />;
  }

  return (
    <span className={styles.poster} data-placeholder="true" style={style} role="presentation" />
  );
}
