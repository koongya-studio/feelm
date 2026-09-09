/*
 * 프레임을 필름 모양으로 표시하는 컨테이너.
 *
 * 와이어프레임 1.3은 이 자리에 이미지 에셋을 넣어 두었고, 필름 시각 표현은
 * `DESIGN_GOALS.md`의 Film motif가 미확정이다(screen-spec.md 8.4). 색상과 무늬는
 * 디자인 확정 후 이 파일에서 교체한다.
 *
 * 와이어프레임이 직접 CSS로 그린 필름 표현은 세 가지다. 모두 실측값이다.
 *
 * | 구성 | 화면 | 배경 | 구멍 |
 * | --- | --- | --- | --- |
 * | 세로 | 2.2 · 2.3 · 2.6 · 2.7 | #E9E7E1 | 폭 12px, 가장자리 5px, 180deg 8px / 21px |
 * | 가로 · 화면 폭 | 3.1 | #3C3A36 | 높이 6px, 가장자리 8px, 90deg 8px / 20px |
 * | 가로 · 내용 폭 | 3.1-A | #3C3A36 | 높이 5px, 가장자리 5px, 90deg 7px / 16px |
 *
 * `orientation`이 `horizontal`일 때 `width`를 주지 않으면 화면 폭을 채우고 안쪽이
 * 가로로 스크롤된다(3.1). `width`를 주면 내용 폭에 맞춰 감싼다(3.1-A).
 * 와이어프레임 3.1은 프레임 줄을 `overflow: hidden`으로 두었으나 screen-spec.md 3.1이
 * `프레임(포스터) 가로 스크롤`로 적고 있어 스크롤로 구현한다.
 *
 * 기존 화면(1.3 · 2.6 · 2.7)이 쓰는 기본값은 세로 · 밝은 색이며 바뀌지 않았다.
 *
 * 2.2 오늘의 추천이 같은 표현을 화면 안에 직접 가지고 있다. 승격 기준(두 번째 화면이
 * 필요해질 때)에 맞으므로 2.2를 이 컴포넌트로 바꾸는 것이 맞지만, 이미 병합된 다른
 * 업무의 화면이라 이 업무에서 손대지 않았다. 별도로 정리한다.
 */
import type { CSSProperties, ReactNode } from 'react';
import styles from './FilmStrip.module.css';

type Props = {
  /**
   * 스트립 폭(px). 세로 방향은 항상 이 폭을 쓴다(와이어프레임 2.2는 224px).
   * 가로 방향은 생략하면 화면 폭을 채운다.
   */
  width?: number;
  /** 방향. 기본값은 기존 화면과 같은 세로 */
  orientation?: 'vertical' | 'horizontal';
  /**
   * 색조. 기본값은 기존 화면과 같은 밝은 색이다.
   *
   * `paper`는 신규 디자인의 밝은 판이다. 반투명 흰색 바탕에 얇은 테두리를 두르고
   * 구멍을 잍크로 뚫는다. 밝은 벅 위에 필름이 놓인 모양으로, 3.1이 쓴다
   * (`docs/screens/design-concept.md`).
   */
  tone?: 'light' | 'dark' | 'paper';
  children?: ReactNode;
};

const DEFAULT_VERTICAL_WIDTH = 224;

export default function FilmStrip({
  width,
  orientation = 'vertical',
  tone = 'light',
  children,
}: Props) {
  const vertical = orientation === 'vertical';
  const resolvedWidth = vertical ? (width ?? DEFAULT_VERTICAL_WIDTH) : width;

  const style = {
    '--film-strip-width': resolvedWidth === undefined ? '100%' : `${resolvedWidth}px`,
  } as CSSProperties;

  return (
    <div
      className={styles.strip}
      style={style}
      data-orientation={orientation}
      data-tone={tone}
      data-fit={resolvedWidth === undefined ? 'fill' : 'hug'}
      data-testid="film-strip"
    >
      <span className={styles.perforation} data-side="start" aria-hidden="true" />
      <span className={styles.perforation} data-side="end" aria-hidden="true" />

      {vertical ? children : <div className={styles.track}>{children}</div>}
    </div>
  );
}
