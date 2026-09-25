/*
 * 일시 오류 자동 재시도 (업무 S15P21E106-893)
 *
 * 무엇을 다시 부르고 무엇을 곧바로 넘기는지를 고정한다. 영구 오류까지 다시 부르면 오류 화면이
 * 늦게 뜰 뿐이고, 일시 오류를 넘기면 MY 첫 진입에서 오류 화면이 잠깐 보인다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, NETWORK_ERROR, UNEXPECTED_ERROR } from './apiError';
import { isTransientFailure, retryTransient } from './retry';
import { endSession, resetSessionForTest } from './session';

beforeEach(() => {
  resetSessionForTest();
});

describe('isTransientFailure', () => {
  it('응답이 없거나 5xx면 일시 오류다', () => {
    expect(isTransientFailure(new ApiError(0, NETWORK_ERROR, '연결 실패'))).toBe(true);
    expect(isTransientFailure(new ApiError(500, 'INTERNAL_ERROR', '서버 오류'))).toBe(true);
    expect(isTransientFailure(new ApiError(503, 'SERVICE_UNAVAILABLE', '점검 중'))).toBe(true);
    // 프록시 오류 페이지처럼 규약 밖 본문이어도 5xx면 서버 사정이다
    expect(isTransientFailure(new ApiError(502, UNEXPECTED_ERROR, '이해할 수 없음'))).toBe(true);
  });

  it('401은 세션을 아직 되살릴 수 있을 때만 일시 오류다', () => {
    const unauthorized = new ApiError(401, 'TOKEN_EXPIRED', '만료');

    expect(isTransientFailure(unauthorized)).toBe(true);

    // 재발급이 거절돼 세션이 끝났으면 다시 불러도 같다
    endSession('expired');
    expect(isTransientFailure(unauthorized)).toBe(false);
  });

  it('나머지 4xx와 규약 밖 오류는 일시 오류가 아니다', () => {
    expect(isTransientFailure(new ApiError(400, 'VALIDATION_FAILED', '잘못'))).toBe(false);
    expect(isTransientFailure(new ApiError(403, 'FORBIDDEN', '금지'))).toBe(false);
    expect(isTransientFailure(new ApiError(404, 'USER_NOT_FOUND', '없음'))).toBe(false);
    expect(isTransientFailure(new Error('버그'))).toBe(false);
  });
});

describe('retryTransient', () => {
  const transient = () => new ApiError(0, NETWORK_ERROR, '연결 실패');

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('일시 오류면 정해진 시간을 기다렸다 다시 부르고, 성공하면 그 값을 준다', async () => {
    const run = vi.fn().mockRejectedValueOnce(transient()).mockResolvedValue('ok');
    const result = retryTransient(run, { delays: [100, 200] });

    await vi.advanceTimersByTimeAsync(99);
    expect(run).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    await expect(result).resolves.toBe('ok');
    expect(run).toHaveBeenCalledTimes(2);
  });

  it('횟수를 다 쓰면 마지막 실패를 던진다', async () => {
    const last = transient();
    const run = vi
      .fn()
      .mockRejectedValueOnce(transient())
      .mockRejectedValueOnce(transient())
      .mockRejectedValueOnce(last);
    const result = retryTransient(run, { delays: [100, 200] });
    const settled = expect(result).rejects.toBe(last);

    await vi.advanceTimersByTimeAsync(300);
    await settled;
    expect(run).toHaveBeenCalledTimes(3);
  });

  it('영구 오류는 기다리지 않고 곧바로 던진다', async () => {
    const notFound = new ApiError(404, 'USER_NOT_FOUND', '없음');
    const run = vi.fn().mockRejectedValue(notFound);

    await expect(retryTransient(run, { delays: [100, 200] })).rejects.toBe(notFound);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('기다리는 사이 끊기면 더 부르지 않고 그 실패를 던진다', async () => {
    const first = transient();
    const run = vi.fn().mockRejectedValueOnce(first).mockResolvedValue('ok');
    const controller = new AbortController();
    const result = retryTransient(run, { delays: [100], signal: controller.signal });
    const settled = expect(result).rejects.toBe(first);

    await vi.advanceTimersByTimeAsync(50);
    controller.abort();
    await settled;

    await vi.advanceTimersByTimeAsync(100);
    expect(run).toHaveBeenCalledTimes(1);
  });
});
