/*
 * 프로필 사진 배경 틀(업무 -697). 7.1 MY · 7.2-A · 7.2-B가 쓴다.
 *
 * **2.4 영화 상세와 같은 층 구성이다**(사용자 지시 2026-09-19 "영화 상세페이지처럼 똑같이").
 * 영화 상세가 포스터를 까는 자리에 프로필 사진을 깐다.
 *
 *   backdrop  화면에 고정된 사진. 스크롤에 따라 흐려지고 어두워진다
 *   scrim     글자가 시작되는 지점부터 덮는 어두운 그라디언트
 *   top       머리(헤더). `stickyTop`이면 위에 붙어 따라오고 뒤에 어두운 띠가 짙어진다
 *   stage     사진이 선명하게 보이는 빈 구간. 사진이 없으면 짧다
 *   sheet     본문. 이어지는 세로 스크롤이고 반투명 경사로 시작한다
 *
 * 사진이 없으면(`avatarUrl: null`) 2.4의 포스터 없는 영화처럼 어두운 단색으로 떨어진다.
 * 기본 자리표시자를 전면에 늘리지 않는다.
 */
import { useRef } from 'react';
import { useScrollFade } from '../../hooks/useScrollFade';
import styles from './ProfileBackdrop.module.css';
import type { CSSProperties, ReactNode } from 'react';

/** 이만큼(화면 높이 비율) 내려가면 배경이 가장 흐리고 어둡다. 2.4와 같은 값이다 */
const BACKDROP_FADE_RATIO = 0.55;

type Props = {
  /** 배경으로 까는 프로필 사진. 없으면 어두운 단색이다 */
  photoUrl: string | null;
  /** 머리. 헤더가 들어간다 */
  top: ReactNode;
  /**
   * 머리를 위에 붙여 따라오게 한다. 뒤로 가기가 있는 7.2가 쓴다. 7.1은 다른 탭 화면(필름 ·
   * 파티)처럼 머리가 내용과 함께 올라간다 — 머리가 높아 붙여 두면 화면 위를 크게 가린다
   */
  stickyTop?: boolean;
  children: ReactNode;
};

export default function ProfileBackdrop({ photoUrl, top, stickyTop = false, children }: Props) {
  const pageRef = useRef<HTMLDivElement>(null);

  useScrollFade(pageRef, '--profile-scroll', BACKDROP_FADE_RATIO, [photoUrl]);

  const style = photoUrl
    ? ({ '--profile-photo': `url("${photoUrl}")` } as CSSProperties)
    : undefined;

  return (
    <div
      ref={pageRef}
      className={styles.page}
      data-photo={photoUrl ? 'image' : 'none'}
      data-testid="profile-backdrop"
      style={style}
    >
      <span className={styles.backdrop} aria-hidden="true" />
      <span className={styles.scrim} aria-hidden="true" />

      <div className={styles.top} data-sticky={stickyTop || undefined}>
        {top}
      </div>
      <div className={styles.stage} aria-hidden="true" />
      <div className={styles.sheet}>{children}</div>
    </div>
  );
}
