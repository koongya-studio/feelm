/*
 * 취향 키워드 · 태그.
 * 와이어프레임 3.1 취향 키워드와 5.9 태그에서 같은 사양으로 확인된다.
 * padding 3px 10px, 완전 둥근 모서리, 테두리 1px, 11px/600
 *
 * 이번 범위에서는 조작할 수 없다. 나중에 필터로 쓰이면 선택 상태를 추가한다.
 *
 * 업무 -305에서 앞쪽 몇 개를 채운 칩으로 그려 대표 키워드를 구분하는 `variant`가 붙었으나
 * **업무 -919에서 걷어냈다.** 키워드 수가 사용자마다 2~5개로 달라(API-040) 화면마다 채운 칩과
 * 테두리 칩의 비율이 제각각이 됐다. 옆 패널은 앞의 셋을, 대시보드와 프로필은 앞의 둘을 채워
 * 같은 사람의 같은 키워드가 화면마다 다르게 보였다. 이제 모두 한 모양이다.
 */
import type { ReactNode } from 'react';
import styles from './Chip.module.css';

type Props = {
  /**
   * 어느 면에 놓이는지.
   *
   * `light` 밝은 면 (기본값). 테두리가 잉크 18%, 채운 칩이 잉크 바탕이다
   * `dark`  어두운 면. 그 색들이 배경에 묻히므로 흰 계열로 뒤집는다
   */
  tone?: 'light' | 'dark';
  /**
   * 앞에 `#`을 붙여 해시태그처럼 보인다.
   *
   * 취향 키워드를 눈에 띄게 하려는 것이다(업무 -919). 라벨 문자열에 섞지 않고 여기서 그리므로
   * 서버가 준 값(API-040 `label`)은 그대로다. 반기 리포트의 키워드는 `rationale`과 함께 나오는
   * 다른 성격이라 붙이지 않는다(사용자 결정 2026-09-26).
   */
  hashtag?: boolean;
  children: ReactNode;
};

export default function Chip({ tone = 'light', hashtag = false, children }: Props) {
  return (
    <span className={styles.chip} data-tone={tone}>
      {hashtag ? <span aria-hidden="true">#</span> : null}
      {children}
    </span>
  );
}
