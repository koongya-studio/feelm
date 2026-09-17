/*
 * 취향 필름 릴. 3.1이 쓰는 화면 중심 콘텐츠다.
 *
 * 사용자 시안 2026-09-17(업무 -458)이 정한 재질이다.
 * **검정에 은은한 주황빛이 도는 반투명 · 얇은 테두리 · 좁은 프레임 간격 ·
 * 위아래 일정한 구멍 · 살짝 휘어진 형태.**
 * 아이스 화이트 팝콘 버킷의 필름 무늬(검정 띠 · 밝은 사각 구멍)와 한 세트로 읽힌다.
 *
 * 기존 `FilmStrip`을 고치지 않고 새로 만들었다. 그 컴포넌트는 1.3 · 2.6 · 3.1-A가
 * 쓰는 밝은 판이고, 세 화면은 이 업무의 시안 범위 밖이다. **네 화면의 필름 표현을
 * 하나로 합치는 것은 후속 업무다.**
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
  /** 프레임 한 칸의 폭(px). 프레임 수와 무관하게 고정이다 */
  frameWidth: number;
  /** 프레임 한 칸의 높이(px) */
  frameHeight: number;
  /** 가로 스크롤 영역에 읽히는 이름 */
  label: string;
  /** 프레임 칸들. 버튼이든 자리표시자든 이 컴포넌트가 테두리를 입힌다 */
  children: ReactNode;
};

export default function FilmReel({ frameWidth, frameHeight, label, children }: Props) {
  const style = {
    '--film-frame-width': `${frameWidth}px`,
    '--film-frame-height': `${frameHeight}px`,
  } as CSSProperties;

  return (
    <div className={styles.reel} style={style} data-testid="film-reel">
      <span className={styles.band} aria-hidden="true">
        <span className={styles.holes} data-edge="top" />
        <span className={styles.holes} data-edge="bottom" />
      </span>

      {/*
       * 스크롤 영역은 키보드로도 닿아야 한다. 프레임이 없는 빈 상태에서는 안쪽에
       * 초점을 받을 요소가 없어 스크롤 자체가 초점을 받아야 하기 때문이다.
       */}
      <div className={styles.track} role="group" aria-label={label} tabIndex={0}>
        {children}
      </div>
    </div>
  );
}
