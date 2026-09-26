import { retryTransient } from './retry';

/** SSE 갱신 뒤 REST가 일시 실패해도 다음 알림을 기다리지 않고 제한적으로 재시도한다. */
export function retryNotificationQuery<T>(run: () => Promise<T>, signal: AbortSignal): Promise<T> {
  return retryTransient(run, { signal, delays: [1000, 2000, 4000, 8000, 16000, 30000] });
}
