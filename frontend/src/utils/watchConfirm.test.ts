/*
 * 감상 확인 시점 안내 (업무 S15P21E106-766)
 *
 * 계약이 클릭 즉시(FR-18, API-034)라 기본값은 바로 도착한다고 말한다. 정책이 다시 바뀌어
 * 지연을 두면 값 하나만 고쳐도 문구가 따라오는지 본다.
 */
import { describe, expect, it } from 'vitest';
import { WATCH_CONFIRM_DELAY_HOURS, watchConfirmTiming } from './watchConfirm';

describe('감상 확인 시점 안내', () => {
  it('계약대로 클릭 즉시다', () => {
    expect(WATCH_CONFIRM_DELAY_HOURS).toBe(0);
    expect(watchConfirmTiming()).toBe('감상 확인 알림은 OTT로 이동하면 바로 도착해요');
  });

  it('지연을 두면 그 시간을 말한다', () => {
    expect(watchConfirmTiming(24)).toBe('감상 확인 알림은 OTT 이동 1일 후에 도착해요');
    expect(watchConfirmTiming(6)).toBe('감상 확인 알림은 OTT 이동 6시간 후에 도착해요');
  });
});
