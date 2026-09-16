/*
 * 4컷 인화지에 붙이는 빨간 테이프.
 *
 * 7.3 취향 리포트 목록이 벽에 붙인 인화지를 그렇게 그렸다(사용자 시안 2026-09-15, 업무 -446).
 * 7.1 MY의 `4컷 리포트` 바로가기가 같은 모양을 쓰면서 승격했다(업무 -459).
 *
 * **빨강은 붙인 종이의 일부다.** 앱 UI의 무채색 규칙(`design-concept.md` 3장)은 인화지
 * 바깥에 적용되고, 테이프는 인화지와 한 덩어리로 본다.
 *
 * 끝이 뜯긴 모양은 좌우 톱니로 자른다. 크기와 기울기는 붙일 자리마다 다르므로 CSS 변수로
 * 받는다 — `--tape-width` · `--tape-height` · `--tape-top` · `--tape-shift` · `--tape-angle`.
 * 기본값은 7.3의 값이다.
 *
 * 붙는 자리(`position: relative`)는 쓰는 쪽이 만든다.
 */
import styles from './ReportTape.module.css';

export default function ReportTape() {
  return <span className={styles.tape} aria-hidden="true" />;
}
