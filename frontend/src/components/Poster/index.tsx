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
 *
 * **주소가 있어도 이미지를 못 받으면 자리표시자로 물러선다**(업무 -770). 전에는 깨진 이미지
 * 아이콘과 빈 테두리가 남았다.
 *
 * `caption`을 주면 자리표시자 위에 적는다. 포스터가 **그 화면의 내용 자체**인 곳에서만 쓴다 —
 * 2.6 · 2.7은 방금 평가한 영화가 무엇인지 포스터로만 말하는데, 포스터 없는 영화면 빈 칸이라
 * 무엇이 추가됐는지 알 수 없었다. 목록의 칸처럼 제목이 곁에 따로 있는 곳에는 쓰지 않는다.
 */
import { useState } from 'react';
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
  /**
   * 담긴 자리의 폭을 채운다. 높이는 `width` · `height`의 비율로 따라간다(7.1의 3열 격자).
   *
   * 격자는 화면 폭에 따라 한 칸의 폭이 달라져 실측 px을 쓸 수 없다. 비율만 고정한다.
   */
  fluid?: boolean;
  /**
   * 이미지를 불러오는 시점. 기본은 화면에 가까워질 때(`lazy`)다.
   *
   * 들어오자마자 봐야 하는 포스터는 `eager`로 준다. 지연 로딩은 요청을 레이아웃 뒤로 미루므로,
   * 1.2초 뒤 넘어가는 2.6처럼 짧은 장면에서는 빈 칸만 보이고 지나간다.
   */
  loading?: 'lazy' | 'eager';
  /** 자리표시자에 적는 글. 보통 영화 제목이다. 포스터가 있으면 쓰지 않는다 */
  caption?: string;
};

export default function Poster({
  width,
  height,
  radius = 8,
  src,
  title,
  seed = 0,
  fluid = false,
  loading = 'lazy',
  caption,
}: Props) {
  const [dark, light] = posterTone(seed);
  /** 받지 못한 주소. 주소가 바뀌면 다시 시도한다 */
  const [failedSrc, setFailedSrc] = useState<string>();

  const style = {
    '--poster-width': `${width}px`,
    '--poster-height': `${height}px`,
    '--poster-ratio': `${width} / ${height}`,
    '--poster-radius': `${radius}px`,
    '--poster-dark': dark,
    '--poster-light': light,
  } as CSSProperties;

  const fit = fluid ? 'true' : undefined;

  if (src && src !== failedSrc) {
    return (
      <img
        className={styles.poster}
        data-fluid={fit}
        style={style}
        src={src}
        alt={title}
        loading={loading}
        onError={() => setFailedSrc(src)}
      />
    );
  }

  return (
    <span
      className={styles.poster}
      data-placeholder="true"
      data-fluid={fit}
      style={style}
      /*
       * 제목을 적은 자리표시자는 장식이 아니다. `presentation`으로 두면 적은 제목까지 접근성
       * 트리에서 빠져, 이미지가 실패했을 때 `alt`로 읽히던 제목을 오히려 잃는다(!541 리뷰)
       */
      role={caption ? undefined : 'presentation'}
    >
      {caption ? <span className={styles.caption}>{caption}</span> : null}
    </span>
  );
}
