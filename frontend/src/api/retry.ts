/*
 * 일시 오류 자동 재시도 (업무 S15P21E106-893)
 *
 * 모바일에서 MY 탭에 처음 들어갈 때 재발급 · 네트워크 · 백엔드가 잠깐 실패하면 프로필 오류
 * 화면이 떴다가, 탭을 나갔다 오면 멀쩡해졌다. 그런 실패만 골라 짧게 기다렸다 다시 부른다.
 *
 * **전역 재시도가 아니다.** 부른 쪽이 고른 조회에만 쓴다. GET이라도 부작용이 있는 조회가
 * 있을 수 있어 HTTP 클라이언트에 일괄로 걸면 같은 부작용이 겹친다.
 *
 * 다시 부르는 실패
 * - 응답이 없다(`NETWORK_ERROR`, 상태 0)
 * - 401인데 세션을 아직 되살릴 수 있다(`canRefresh()`). 클라이언트가 한 번 재발급했지만
 *   재발급 요청 자체가 네트워크 · 서버 사정으로 실패한 경우다. 재발급이 거절되면 세션이
 *   끝나 `canRefresh()`가 내려가므로 다시 부르지 않는다
 * - 5xx
 *
 * 나머지 4xx는 다시 불러도 같은 답이라 곧바로 넘긴다.
 */
import { NETWORK_ERROR, isApiError } from './apiError';
import { canRefresh } from './session';

/** 첫 요청이 실패한 뒤 기다리는 시간. 길이가 곧 재시도 횟수다 */
export const RETRY_DELAYS_MS: readonly number[] = [400, 1200];

export function isTransientFailure(error: unknown): boolean {
  if (!isApiError(error)) return false;
  if (error.code === NETWORK_ERROR) return true;
  if (error.status === 401) return canRefresh();

  return error.status >= 500;
}

/**
 * `run`을 부르고, 일시 오류면 `delays`만큼 기다렸다 다시 부른다. 마지막 실패는 그대로 던진다.
 *
 * `signal`이 끊기면 더 부르지 않는다 — 화면을 떠난 뒤 요청을 이어 보내지 않는다. 이미 나간
 * 요청의 응답은 막지 못하므로 부른 쪽이 따로 무시해야 한다.
 */
export async function retryTransient<T>(
  run: () => Promise<T>,
  { signal, delays = RETRY_DELAYS_MS }: { signal?: AbortSignal; delays?: readonly number[] } = {},
): Promise<T> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await run();
    } catch (error) {
      if (attempt >= delays.length || !isTransientFailure(error) || signal?.aborted) throw error;

      await wait(delays[attempt], signal);

      if (signal?.aborted) throw error;
    }
  }
}

function wait(ms: number, signal: AbortSignal | undefined): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(done, ms);

    function done() {
      clearTimeout(timer);
      signal?.removeEventListener('abort', done);
      resolve();
    }

    signal?.addEventListener('abort', done, { once: true });
  });
}
