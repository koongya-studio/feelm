import { canRefresh, getAccessToken, onSessionEnd, refreshAccessToken } from './session';

const listeners = new Set<() => void>();
export function notifyNotificationChange() {
  for (const listener of listeners) listener();
}
export function onNotificationChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** 로그인 세션마다 스트림 하나를 열고 알림 내용은 REST로 조회한다. */
export function startNotificationStream(): () => void {
  const controller = new AbortController();
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  let attempt = 0;
  let stopped = false;
  const stop = () => {
    stopped = true;
    controller.abort();
    clearTimeout(retryTimer);
    unsubscribe();
  };
  const unsubscribe = onSessionEnd(stop);
  const retry = () => {
    if (!stopped) {
      const delay = Math.min(30_000, 1000 * 2 ** Math.min(attempt++, 5));
      retryTimer = setTimeout(() => {
        void connect();
      }, delay);
    }
  };
  async function connect() {
    const connection = new AbortController();
    const abort = () => connection.abort();
    controller.signal.addEventListener('abort', abort, { once: true });
    try {
      let token = getAccessToken();
      if (!token || stopped) return;
      const send = async () => {
        const timer = setTimeout(() => connection.abort(), 45_000);
        try {
          return await fetch('/api/v1/notifications/stream', {
            headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
            credentials: 'include',
            signal: connection.signal,
          });
        } finally {
          clearTimeout(timer);
        }
      };
      let response = await send();
      if (stopped) {
        await response.body?.cancel();
        return;
      }
      if (response.status === 401 && canRefresh()) {
        await response.body?.cancel();
        token = await refreshAccessToken();
        if (stopped) return;
        if (!token) {
          retry();
          return;
        }
        response = await send();
      }
      if (stopped) {
        await response.body?.cancel();
        return;
      }
      if (
        !response.ok ||
        !response.body ||
        !response.headers.get('content-type')?.includes('text/event-stream')
      ) {
        await response.body?.cancel();
        retry();
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      const parser = createNotificationParser(() => {
        if (!stopped) {
          attempt = 0;
          notifyNotificationChange();
        }
      });
      notifyNotificationChange(); // 최초 연결과 재연결 때 DB 정본을 다시 조회한다
      let idleTimer: ReturnType<typeof setTimeout> | undefined;
      const resetIdle = () => {
        clearTimeout(idleTimer);
        idleTimer = setTimeout(() => {
          void reader.cancel();
        }, 45_000);
      };
      try {
        resetIdle();
        while (!stopped) {
          const { value, done } = await reader.read();
          if (done) break;
          resetIdle();
          parser(decoder.decode(value, { stream: true }));
        }
      } finally {
        clearTimeout(idleTimer);
        await reader.cancel().catch(() => undefined);
        reader.releaseLock();
      }
      retry();
    } catch {
      retry();
    } finally {
      controller.signal.removeEventListener('abort', abort);
    }
  }
  void connect();
  return stop;
}

/** UTF-8 디코딩된 SSE를 행 단위로 처리하며 청크 경계와 CRLF를 지원한다. */
export function createNotificationParser(onChange: () => void): (chunk: string) => void {
  let line = '';
  let event = '';
  let hasData = false;
  let afterCr = false;
  function endLine() {
    if (line === '') {
      if (event === 'notification-changed' && hasData) onChange();
      event = '';
      hasData = false;
    } else if (line.startsWith('event:')) {
      event = line.slice(6).replace(/^ /, '');
    } else if (line.startsWith('data:')) {
      hasData = true;
    }
    line = '';
  }
  return (chunk) => {
    for (const char of chunk) {
      if (char === '\n' && afterCr) {
        afterCr = false;
        continue;
      }
      afterCr = false;
      if (char === '\r' || char === '\n') {
        endLine();
        afterCr = char === '\r';
      } else {
        line += char;
        if (line.length > 65_536) throw new Error('SSE frame too large');
      }
    }
  };
}
