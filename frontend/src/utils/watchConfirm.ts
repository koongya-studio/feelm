/*
 * 감상 확인 알림이 오는 시점 (FR-18, API-034 · 035, 업무 S15P21E106-766).
 *
 * **값은 여기 한 곳이다.** 화면이 이 시점을 말하는 자리(7.8-C 알림 설정)는 모두 이 파일의
 * 문구를 쓴다. 한때 `OTT 이동 2일 후`라고 적혀 있었는데 그때도 계약은 24시간이었다.
 *
 * 계약은 **클릭 즉시**다 — API-034 처리 규칙 `confirm_due_at은 clicked_at과 같다`,
 * FR-18 `24시간 지연을 없애고 클릭 즉시로 전환`(팀 결정 2026-09-18, 업무 -667 · -669).
 * 백엔드 구현(-667)이 배포되기 전까지는 서버가 아직 24시간 뒤에 묻는다. 정책이 다시 바뀌면
 * `WATCH_CONFIRM_DELAY_HOURS`만 고친다.
 */

/** OTT 링크를 누른 뒤 감상 확인을 묻기까지의 시간. 0이면 바로 묻는다 */
export const WATCH_CONFIRM_DELAY_HOURS = 0;

/** 7.8-C 맨 아래 안내 */
export function watchConfirmTiming(delayHours: number = WATCH_CONFIRM_DELAY_HOURS): string {
  if (delayHours <= 0) return '감상 확인 알림은 OTT로 이동하면 바로 도착해요';
  if (delayHours % 24 === 0) return `감상 확인 알림은 OTT 이동 ${delayHours / 24}일 후에 도착해요`;

  return `감상 확인 알림은 OTT 이동 ${delayHours}시간 후에 도착해요`;
}
