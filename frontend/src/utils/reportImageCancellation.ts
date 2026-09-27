/** iOS 캡처의 대기를 취소한다. 늦게 끝난 작업은 다음 캡처 단계로 이어지지 않는다. */
export function assertCaptureActive(signal?: AbortSignal): void {
  if (signal?.aborted) throw signal.reason ?? new Error('ReportFourCut capture canceled');
}

export function waitForCapture<T>(
  operation: () => PromiseLike<T>,
  signal?: AbortSignal,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const remove = () => signal?.removeEventListener('abort', abort);
    const abort = () => {
      remove();
      reject(signal?.reason ?? new Error('ReportFourCut capture canceled'));
    };
    if (signal?.aborted) {
      abort();
      return;
    }
    signal?.addEventListener('abort', abort, { once: true });
    Promise.resolve()
      .then(() => {
        assertCaptureActive(signal);
        return operation();
      })
      .then(
        (value) => {
          remove();
          if (signal?.aborted) abort();
          else resolve(value);
        },
        (error: unknown) => {
          remove();
          reject(error);
        },
      );
  });
}

/** 제한 시간에 취소하고 작업의 finally 복원이 끝난 뒤 실패를 반환한다. */
export async function withCaptureDeadline<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  duration: number,
  message: string,
  parent?: AbortSignal,
): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort(parent?.reason);
  if (parent?.aborted) abort();
  else parent?.addEventListener('abort', abort, { once: true });
  const timer = window.setTimeout(() => controller.abort(new Error(message)), duration);
  try {
    assertCaptureActive(controller.signal);
    return await operation(controller.signal);
  } finally {
    window.clearTimeout(timer);
    parent?.removeEventListener('abort', abort);
  }
}
