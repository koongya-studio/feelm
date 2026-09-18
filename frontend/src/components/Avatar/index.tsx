/*
 * 프로필 이미지.
 *
 * 여러 크기로 쓴다. 7.1 · 7.2-A · 7.2-B 프로필 88 · 7.2 편집 104 · 7.8-D 차단 목록 38이다.
 * 크기가 화면마다 다르므로 props로 받는다(Poster와 같은 규칙). 모양은 늘 동그라미다 —
 * 7.1의 3:4 세로 사진은 2026-09-18 시안에서 동그라미로 돌아왔다(업무 -616).
 *
 * `src`가 없으면 자리표시자를 그린다. **실제 프로필 이미지는 사용자가 올리는 것이라
 * 목업 단계에서는 항상 비어 있다.** 같은 회색 원만 두면 파티원 넷이 구분되지 않으므로
 * **닉네임에서 밝기 단계를 뽑는다.** 같은 사람은 항상 같은 밝기다. 색조는 쓰지 않는다.
 */
import type { CSSProperties } from 'react';
import { PersonIcon } from '../Icon';
import styles from './Avatar.module.css';

type Props = {
  /** 지름(px) */
  size: number;
  src?: string | null;
  /** 대체 텍스트의 바탕이 되는 이름. 자리표시자 밝기도 여기서 나온다 */
  nickname: string;
};

/**
 * 자리표시자 밝기 4단계. **무채색이다.**
 *
 * 확정 방향은 `UI에 유채색을 쓰지 않는다`이고 `화면에 색이 보이면 그것은 콘텐츠`다
 * (`design-concept.md` 2.2 · 3장). 프로필 이미지가 없는 자리는 콘텐츠가 아니므로
 * 색을 가질 수 없다. 밝기만 다르게 해 파티원 목록에서 서로 구분되게 한다.
 *
 * 포스터(`Poster/tone.ts`)보다 옅다 — 아바타는 작고 여러 개가 나란히 놓이므로
 * 포스터만큼 어두우면 화면이 무거워진다.
 */
const TONES: [string, string][] = [
  ['#4e4f55', '#6a6b70'],
  ['#54555b', '#717277'],
  ['#46474d', '#616268'],
  ['#5b5c62', '#78797e'],
];

/** 닉네임을 밝기 단계로 바꾼다. 같은 이름이면 항상 같은 값이다 */
function toneIndex(nickname: string): number {
  let sum = 0;

  for (const char of nickname) {
    sum += char.codePointAt(0) ?? 0;
  }

  return sum % TONES.length;
}

export default function Avatar({ size, src, nickname }: Props) {
  const [dark, light] = TONES[toneIndex(nickname)];

  const style = {
    '--avatar-size': `${size}px`,
    '--avatar-dark': dark,
    '--avatar-light': light,
  } as CSSProperties;

  if (src) {
    return (
      <img
        className={styles.avatar}
        style={style}
        src={src}
        alt={`${nickname}의 프로필 이미지`}
        loading="lazy"
      />
    );
  }

  return (
    <span className={styles.avatar} data-placeholder="true" style={style} role="presentation">
      <PersonIcon />
    </span>
  );
}
