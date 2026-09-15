/*
 * 영화 포스터.
 *
 * 크기와 모서리 반경은 화면마다 다르므로 props로 받는다. 와이어프레임 실측값이다.
 * 2.1 대표 104x148 r8 · 2.2 카드 156x220 r4 · 3.1 프레임 118x166 r4
 * 1.1 온보딩 카드 58x82 r8 · 3.1 최근 목록 34x48 r6
 *
 * `src`가 없으면 자리표시자를 표시한다. **실제 포스터 이미지의 권리가 확정되지 않아
 * 목업 단계에서는 자리표시자가 화면의 절반을 차지한다**(`PRODUCT.md`). 실제 이미지가
 * 들어오면 자리표시자는 쓰이지 않는다.
 *
 * 자리표시자는 **무채색**이다. 밝기만 4단계로 달라 여러 장이 나란히 놓일 때 서로
 * 구분된다. 어느 단계를 쓸지는 `seed`(보통 `movieId`)로 정하므로 같은 영화는 항상
 * 같은 밝기다. 제목이 자리표시자(`영화 제목`)라 제목에서는 뽑을 수 없다.
 *
 * 값과 무채색인 이유는 `tone.ts`에 적었다.
 */
import type { CSSProperties } from 'react';
import { posterTone } from './tone';
import styles from './Poster.module.css';

type Props = {
  width: number;
  height: number;
  radius?: number;
  /** 포스터 이미지 주소. 없으면 자리표시자 */
  src?: string;
  /** 영화 제목. 대체 텍스트가 된다 */
  title: string;
  /** 자리표시자 색조를 고르는 값. 보통 `movieId` */
  seed?: number;
};

export default function Poster({ width, height, radius = 8, src, title, seed = 0 }: Props) {
  const [dark, light] = posterTone(seed);

  const style = {
    '--poster-width': `${width}px`,
    '--poster-height': `${height}px`,
    '--poster-radius': `${radius}px`,
    '--poster-dark': dark,
    '--poster-light': light,
  } as CSSProperties;

  if (src) {
    return <img className={styles.poster} style={style} src={src} alt={title} loading="lazy" />;
  }

  return (
    <span className={styles.poster} data-placeholder="true" style={style} role="presentation" />
  );
}
