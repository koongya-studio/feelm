import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createNotificationParser,
  onNotificationChange,
  startNotificationStream,
} from './notificationStream';
import { endSession, resetSessionForTest, startSession } from './session';

const fetchMock = vi.fn();
let stop: (() => void) | undefined;
beforeEach(() => {
  vi.useFakeTimers();
  resetSessionForTest();
  startSession('old');
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  stop?.();
  stop = undefined;
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
function stream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(value) {
      controller = value;
    },
  });
  return {
    response: new Response(body, { headers: { 'Content-Type': 'text/event-stream' } }),
    controller,
  };
}

describe('SSE 파싱', () => {
  it('청크 경계·CRLF·주석·다른 이벤트를 구분한다', () => {
    const changed = vi.fn();
    const parse = createNotificationParser(changed);
    for (const char of ': heartbeat\r\n\r\nevent: other\ndata: {}\n\nevent: notification-changed\r\ndata: {}\r\n\r\n')
      parse(char);
    expect(changed).toHaveBeenCalledTimes(1);
    parse('event: notification-changed\ndata: {}');
    expect(changed).toHaveBeenCalledTimes(1);
    parse('\n\n');
    expect(changed).toHaveBeenCalledTimes(2);
  });
  it('데이터 없는 이벤트와 과도한 행은 받지 않는다', () => {
    const changed = vi.fn();
    const parse = createNotificationParser(changed);
    parse('event: notification-changed\n\n');
    expect(changed).not.toHaveBeenCalled();
    expect(() => parse('x'.repeat(65537))).toThrow();
  });
});

it('Bearer 헤더로 연결하고 이벤트마다 재조회 신호를 보낸다', async () => {
  const source = stream();
  fetchMock.mockResolvedValue(source.response);
  const changed = vi.fn();
  const unsubscribe = onNotificationChange(changed);
  stop = startNotificationStream();
  await vi.advanceTimersByTimeAsync(0);
  expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/notifications/stream');
  expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer old');
  source.controller.enqueue(new TextEncoder().encode('event: notification-changed\ndata: {}\n\n'));
  await vi.waitFor(() => expect(changed).toHaveBeenCalledTimes(2));
  unsubscribe();
});

it('401이면 갱신된 토큰으로 다시 연결한다', async () => {
  const source = stream();
  fetchMock
    .mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ accessToken: 'new', expiresIn: 1800 }), {
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    .mockResolvedValueOnce(source.response);
  stop = startNotificationStream();
  await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
  expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe('Bearer new');
});

it('종료 뒤 backoff로 재연결하며 로그아웃하면 중단한다', async () => {
  const first = stream();
  const second = stream();
  fetchMock.mockResolvedValueOnce(first.response).mockResolvedValueOnce(second.response);
  stop = startNotificationStream();
  await vi.advanceTimersByTimeAsync(0);
  first.controller.close();
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(999);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1);
  expect(fetchMock).toHaveBeenCalledTimes(2);
  const signal = fetchMock.mock.calls[1][1].signal;
  endSession('signed-out');
  expect(signal.aborted).toBe(true);
  await vi.advanceTimersByTimeAsync(60_000);
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it('45초 무수신 연결을 끊고 다시 연결한다', async () => {
  const first = stream();
  const second = stream();
  fetchMock.mockResolvedValueOnce(first.response).mockResolvedValueOnce(second.response);
  stop = startNotificationStream();
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(46_000);
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it('갱신 도중 로그아웃하면 이전 세션을 재연결하지 않는다', async () => {
  let resolve!: (value: Response) => void;
  fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 })).mockImplementationOnce(
    () =>
      new Promise<Response>((done) => {
        resolve = done;
      }),
  );
  stop = startNotificationStream();
  await vi.advanceTimersByTimeAsync(0);
  endSession('signed-out');
  resolve(new Response(JSON.stringify({ accessToken: 'new', expiresIn: 1800 })));
  await vi.advanceTimersByTimeAsync(0);
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it('최초 응답이 멈추면 요청을 중단하고 backoff로 다시 연결한다', async () => {
  fetchMock
    .mockImplementationOnce(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init.signal.addEventListener('abort', () => reject(new Error('aborted')));
        }),
    )
    .mockResolvedValueOnce(stream().response);
  stop = startNotificationStream();
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(46_000);
  expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
  expect(fetchMock).toHaveBeenCalledTimes(2);
});
