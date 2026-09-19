/*
 * 취향 필름 릴. 3.1이 쓰는 화면 중심 콘텐츠다.
 *
 * 사용자 시안 2026-09-17(업무 -458)이 정한 재질이다.
 * **검정에 은은한 주황빛이 도는 반투명 · 얇은 테두리 · 좁은 프레임 간격 ·
 * 위아래 일정한 구멍 · 살짝 휘어진 형태.**
 * 아이스 화이트 팝콘 버킷의 필름 무늬(검정 띠 · 밝은 사각 구멍)와 한 세트로 읽힌다.
 *
 * 처음에는 밝은 판 `FilmStrip`을 두고 새로 만들었다(-458의 시안 범위가 3.1뿐이었다).
 * **1.3 · 2.2는 -605에서, 2.6 · 3.1-A는 -609에서 이 릴의 세로 방향으로 옮겼고** `FilmStrip`은
 * 지웠다(사용자 지시 2026-09-18 — "3.1에 쓴 필름 디자인을 세로로", "같은 릴로"). 앱의 필름은
 * 이제 이 컴포넌트 하나다.
 *
 * 세로 방향은 **화면 흐름 안에서 길어지는 필름**이다. 3.1처럼 스스로 스크롤하지 않고
 * 프레임 수만큼 아래로 자라며, 스크롤은 화면이 맡는다. 1.3은 세 칸이라 다 보이고
 * 2.2는 카드 수만큼 길어져 페이지와 함께 넘어간다. 구멍은 좌우 가장자리에 찍힌다.
 *
 * 휘어짐은 **띠에만 건다.** 띠는 `mask-image`로 위아래 모서리를 완만하게 깎고,
 * 프레임은 그 위에 따로 얹는다. 띠까지 포함해 통째로 변형하면 포스터가 같이 휘어
 * 시안이 금지한 왜곡이 생긴다.
 *
 * 구멍은 뚫지 않고 밝은 사각으로 찍는다. 띠가 반투명이라 실제로 뚫으면 구멍과 바탕의
 * 밝기 차가 거의 없어진다.
 *
 * 프레임이 늘어도 칸 크기는 그대로고 가로로 스크롤한다(시안 — 0 · 3 · 12 · 100개).
 * 칸 크기를 화면 폭에 맞춰 줄이면 100개일 때 포스터가 우표만 해진다.
 */
import type { CSSProperties, ReactNode } from 'react';
import styles from './FilmReel.module.css';

type Props = {
  /**
   * 프레임 한 칸의 폭. 숫자면 px이고, 문자열이면 CSS 길이 그대로다 — 2.2처럼 칸을 화면 폭에
   * 맞춰야 할 때 `calc()`를 넘긴다. 프레임 수와 무관하다
   */
  frameWidth: number | string;
  /** 프레임 한 칸의 높이. `frameWidth`와 같은 규칙이다 */
  frameHeight: number | string;
  /** 프레임 묶음에 읽히는 이름 */
  label: string;
  /**
   * 필름이 놓이는 방향. 기본은 3.1의 가로 릴(스스로 가로 스크롤)이고, `vertical`은
   * 1.3 · 2.2의 세로 필름(화면 흐름을 따라 길어진다)이다.
   */
  orientation?: 'horizontal' | 'vertical';
  /**
   * 띠를 휘지 않고 곧게 둔다. 화면 폭을 꽉 채우는 필름(2.2, 업무 -672)용이다 — 휘어진 띠는
   * 가장자리가 안쪽으로 들어가 화면 끝에 빈틈이 생긴다
   */
  flat?: boolean;
  /** 프레임 칸들. 버튼이든 자리표시자든 이 컴포넌트가 테두리를 입힌다 */
  children: ReactNode;
};

/** 숫자는 px로, 문자열은 CSS 길이 그대로 쓴다 */
function cssLength(value: number | string): string {
  return typeof value === 'number' ? `${value}px` : value;
}

export default function FilmReel({
  frameWidth,
  frameHeight,
  label,
  orientation = 'horizontal',
  flat = false,
  children,
}: Props) {
  const vertical = orientation === 'vertical';
  const style = {
    '--film-frame-width': cssLength(frameWidth),
    '--film-frame-height': cssLength(frameHeight),
  } as CSSProperties;

  return (
    <div
      className={styles.reel}
      style={style}
      data-orientation={orientation}
      data-flat={flat || undefined}
      data-testid="film-reel"
    >
      <span className={styles.band} aria-hidden="true">
        <span className={styles.holes} data-edge={vertical ? 'left' : 'top'} />
        <span className={styles.holes} data-edge={vertical ? 'right' : 'bottom'} />
      </span>

      {/*
       * 가로 릴의 스크롤 영역은 키보드로도 닿아야 한다. 프레임이 없는 빈 상태에서는
       * 안쪽에 초점을 받을 요소가 없어 스크롤 자체가 초점을 받아야 하기 때문이다.
       * 세로 필름은 스스로 스크롤하지 않으므로 초점을 받을 이유가 없다.
       */}
      <div
        className={styles.track}
        role="group"
        aria-label={label}
        tabIndex={vertical ? undefined : 0}
      >
        {children}
      </div>
    </div>
  );
}
