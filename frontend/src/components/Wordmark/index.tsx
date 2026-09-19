/*
 * Feelm 워드마크.
 *
 * **리디자인한 로고다**(업무 S15P21E106-703, 2026-09-19). 필름 띠가 `F`와 `m`을 감고, 가운데
 * `ee`가 웃는 얼굴인 손글씨 워드마크다. 디자인이 준 SVG 원본(`assets/logo/feelm-wordmark.svg`,
 * 1358 × 657) 그대로다. 한때는 서체로 쓴 글자 워드마크였고 그 로고는 폐기했다.
 *
 * 원본은 빨강 한 색이라 **마스크로 칠한다.** 모양은 SVG가, 색은 CSS가 맡는다 — 기본은 브랜드
 * 빨강이고 `inherit`이면 부모 글자색을 따라 어두운 면에도 올릴 수 있다. `<img>`로 넣으면 색을
 * 바꿀 수 없다.
 *
 * 그림이라 `role="img"`와 `aria-label`로 한 단어로 읽힌다.
 */
import styles from './Wordmark.module.css';

type Props = {
  /**
   * 크기 기준(px). 한때 글자 크기였던 값을 그대로 받는다 — 워드마크의 높이는 이 값의 1.4배다.
   * 헤더 기본값 22px이면 높이 약 31px · 폭 약 64px로 이전 글자 워드마크와 폭이 같다.
   *
   * 글자로 주면 CSS 길이다. 취향 4컷처럼 폭에 따라 커지는 자리에서 `calc()`를 넘긴다.
   */
  size?: number | string;
  /**
   * 색 처리. 기본은 브랜드 빨강이다. `inherit`는 부모 색을 따르며, 어두운 면이나
   * 사진 위처럼 빨강이 안 읽히는 자리에 쓴다.
   */
  tone?: 'brand' | 'inherit';
};

export default function Wordmark({ size = 22, tone = 'brand' }: Props) {
  return (
    <span
      className={styles.wordmark}
      style={{ fontSize: size }}
      data-tone={tone}
      role="img"
      aria-label="Feelm"
    />
  );
}
