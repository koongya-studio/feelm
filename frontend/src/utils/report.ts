/*
 * 취향 리포트 표시 형식 (업무 -308).
 */
import type { ReportPeriod } from '../types/report';

/** `H1` → `상반기`. API-059 `half` */
export function formatHalf(period: ReportPeriod): string {
  return period.half === 'H1' ? '상반기' : '하반기';
}

/** 지난 반기와 비교 한 줄의 수치. `+12%` · `−6%` · `+4% 신규`. 빼기는 와이어프레임대로 긴 줄표다 */
export function formatDelta(delta: number, isNew: boolean): string {
  const sign = delta < 0 ? '−' : '+';
  const text = `${sign}${Math.abs(delta)}%`;

  return isNew ? `${text} 신규` : text;
}
