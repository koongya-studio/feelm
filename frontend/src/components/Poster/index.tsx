/*
 * 영화 포스터.
 *
 * 크기와 모서리 반경은 화면마다 다르므로 props로 받는다. 와이어프레임 실측값이다.
 * 2.1 대표 104x148 r8 · 2.2 카드 156x220 r4 · 3.1 프레임 118x166 r4
 * 1.1 온보딩 카드 58x82 r8 · 3.1 최근 목록 34x48 r6
 *
 * `src`가 없으면 자리표시자를 표시한다. **실제 포스터 이미지의 권리가 확정되지 않아
 * 목업 단계에서는 자리표시자가 화면의 절반을 차지한다**(`PRODUCT.md`).
 *
 * 와이어프레임은 그 자리를 회색 대각선 X자로 표현한다. 자리가 비어 있음을 알리는 데는
 * 맞지만, 화면의 절반이 무채색이라 실제 앱처럼 보이지 않는다. 그래서 **인화된 컷**으로
 * 바꿨다(디자인 방향 B안 「암실」, 2026-09-08).
 *
 * - 영화마다 다른 깊은 톤 — 필름을 컬러 그레이딩한 색조 8종
 * - 어느 톤을 쓸지는 `seed`(보통 `movieId`)로 정한다. 같은 영화는 항상 같은 색이다
 * - 제목이 자리표시자(`영화 제목`)라 제목에서 색을 뽑으면 전부 같은 색이 된다
 *
 * 톤은 accent(`#4A0D0D`)를 포함해 그 세계 안에서 골랐다. 실제 이미지가 들어오면
 * 이 자리표시자는 쓰이지 않는다.
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
  /** 자리표시자 색조를 고르는 값. 보통 `movieId` */
  seed?: number;
};

/**
 * 자리표시자 색조 8종. 어두운 쪽 · 밝은 쪽 두 색으로 대각 그라데이션을 만든다.
 * 채도를 낮춰 잉크 세계와 부딪히지 않게 했고, 세 번째가 accent 색조다.
 */
const TONES: [string, string][] = [
  ['#1f2a33', '#3a4b58'], // 심야
  ['#3a2e2a', '#5c4a41'], // 세피아
  ['#4a0d0d', '#7a1e1e'], // 옥스블러드 (accent)
  ['#2a3a33', '#425b50'], // 딥 그린
  ['#332b40', '#524368'], // 플럼
  ['#3d3520', '#605234'], // 올리브 골드
  ['#2e2c29', '#4d4a44'], // 잉크
  ['#42272e', '#6b3d47'], // 와인
];

export default function Poster({ width, height, radius = 8, src, title, seed = 0 }: Props) {
  const [dark, light] = TONES[Math.abs(Math.trunc(seed)) % TONES.length];

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
