/*
 * 취향 리포트 표시 형식 (업무 -308).
 */
import type { ReportPeriod } from '../types/report';

/** `H1` → `상반기`. API-059 `half` */
export function formatHalf(period: ReportPeriod): string {
  return period.half === 'H1' ? '상반기' : '하반기';
}

const MONTHS = [
  'JANUARY',
  'FEBRUARY',
  'MARCH',
  'APRIL',
  'MAY',
  'JUNE',
  'JULY',
  'AUGUST',
  'SEPTEMBER',
  'OCTOBER',
  'NOVEMBER',
  'DECEMBER',
];

/**
 * 4컷에 찍는 인화 날짜. 리포트를 만든 달이다(API-060 `createdAt`).
 * `month`는 머리 오른쪽 `SEPTEMBER 2026`, `stamp`는 로고 아래 `2026.09`다.
 */
export function formatPrintDate(createdAt: string): { month: string; stamp: string } {
  const date = new Date(createdAt);
  const year = date.getFullYear();
  const month = date.getMonth();

  return {
    month: `${MONTHS[month]} ${year}`,
    stamp: `${year}.${String(month + 1).padStart(2, '0')}`,
  };
}

/** 지난 반기와 비교 한 줄의 수치. `+12%` · `−6%` · `+4% 신규`. 빼기는 와이어프레임대로 긴 줄표다 */
export function formatDelta(delta: number, isNew: boolean): string {
  const sign = delta < 0 ? '−' : '+';
  const text = `${sign}${Math.abs(delta)}%`;

  return isNew ? `${text} 신규` : text;
}
