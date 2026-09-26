import { afterEach, expect, it, vi } from 'vitest';
import { retryNotificationQuery } from './notificationRetry';
import { ApiError } from './apiError';

afterEach(() => vi.useRealTimers());
it('SSE 이후 조회가 503이면 새 이벤트 없이도 재시도해 복구한다', async () => {
  vi.useFakeTimers();
  const run = vi
    .fn()
    .mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', 'retry'))
    .mockResolvedValue(3);
  const pending = retryNotificationQuery(run, new AbortController().signal);
  await vi.advanceTimersByTimeAsync(1000);
  await expect(pending).resolves.toBe(3);
  expect(run).toHaveBeenCalledTimes(2);
});
it('화면을 떠나면 예약된 재조회를 중단한다', async () => {
  vi.useFakeTimers();
  const run = vi.fn().mockRejectedValue(new ApiError(503, 'UNAVAILABLE', 'retry'));
  const controller = new AbortController();
  const pending = retryNotificationQuery(run, controller.signal).catch(() => null);
  await vi.advanceTimersByTimeAsync(500);
  controller.abort();
  await expect(pending).resolves.toBeNull();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(run).toHaveBeenCalledTimes(1);
});
