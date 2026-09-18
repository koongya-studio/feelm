/*
 * 인증 세션 상태.
 *
 * Access Token은 **메모리에만** 둔다(팀 결정). `localStorage`에 두면 XSS 한 번에 토큰이
 * 그대로 넘어가고, 새로고침 복구는 httpOnly `refresh_token` 쿠키가 대신하기 때문이다
 * (ADR-0009). 그래서 이 모듈은 새로고침할 때마다 빈 상태로 시작하고, 앱이 뜰 때 재발급
 * 한 번으로 세션을 되살린다(`AuthProvider`).
 *
 * 재발급은 `client`를 거치지 않고 여기서 직접 호출한다. `client`가 401을 만나면 이
 * 모듈을 부르므로 반대 방향 의존을 만들면 순환이 된다.
 */

/** API-006 토큰 재발급. 쿠키 경로(`/api/v1/auth`)와 같은 접두사여야 쿠키가 실린다 */
const REFRESH_URL = '/api/v1/auth/token/refresh';

/** 재발급이 거절됐다는 뜻의 상태 코드. 이것만 세션을 끝낸다 */
const UNAUTHORIZED = 401;

/**
 * 탭 사이의 재발급 잠금 이름.
 *
 * 재발급은 회전이라 한 번 쓴 Refresh Token은 폐기된다(API-006). 아래 `inFlight`는 **이 탭
 * 안에서만** 중복을 막으므로, 같은 쿠키를 공유하는 다른 탭이 동시에 재발급하면 서버가 두
 * 번째 요청을 재사용으로 보게 된다. 서버에 유예가 생겼지만(S15P21E106-596) 애초에 겹쳐
 * 보내지 않는 편이 낫다. 잠금을 못 쓰는 브라우저에서는 유예가 받아 낸다.
 */
const REFRESH_LOCK = 'feelm:auth:refresh';

/** 잠금을 기다리는 한도(ms). 이 시간을 넘기면 조율을 포기하고 그냥 보낸다 */
const LOCK_WAIT_LIMIT_MS = 3000;

let accessToken: string | null = null;

/**
 * 재발급으로 세션을 되살릴 수 있는지.
 *
 * 한 번 거절당하면 다시 로그인하기 전까지 `false`다. 이 값이 없으면 비로그인 사용자가
 * 회원 API를 부를 때마다 실패가 뻔한 재발급을 한 번씩 더 보낸다.
 */
let refreshable = true;

/** 진행 중인 재발급. 동시에 401을 받은 요청들이 재발급을 중복 호출하지 않게 공유한다 */
let inFlight: Promise<string | null> | null = null;

/**
 * 세션이 끝난 이유.
 *
 * `expired`는 쓰고 있던 세션이 재발급 거절로 **사용자가 원치 않게** 끊긴 것이고,
 * `signed-out`은 사용자가 로그아웃한 것이다. 화면이 둘을 같게 다루면 로그아웃 직후에도
 * "다시 로그인해 주세요"가 뜨거나, 반대로 만료를 조용히 넘겨 사용자가 로그인한 줄 알고
 * 실패만 보게 된다.
 *
 * `unauthenticated`는 **애초에 세션이 없었던** 경우다. 앱이 뜰 때의 복구 재발급은
 * 비로그인 방문자에게도 나가고(`AuthProvider`), 쿠키가 없으면 서버는 그것도 401로
 * 거절한다. 이것까지 만료로 다루면 처음 온 사람과 소셜 콜백·이메일 인증 링크가 만료
 * 안내와 함께 로그인 화면으로 밀려난다(리뷰 A-1·B-1).
 */
export type SessionEndReason = 'expired' | 'signed-out' | 'unauthenticated';

const sessionEndListeners = new Set<(reason: SessionEndReason) => void>();

export function getAccessToken(): string | null {
  return accessToken;
}

/** 로그인·재발급이 성공했을 때 */
export function startSession(token: string): void {
  accessToken = token;
  refreshable = true;
}

/** 로그아웃했거나 재발급이 거절당했을 때 */
export function endSession(reason: SessionEndReason): void {
  accessToken = null;
  refreshable = false;

  for (const listener of sessionEndListeners) {
    listener(reason);
  }
}

export function canRefresh(): boolean {
  return refreshable;
}

/**
 * 세션이 끊긴 것을 구독한다.
 *
 * 로그아웃은 화면이 직접 호출하므로 알 수 있지만, 재발급 거절은 아무 API 호출 도중에나
 * 일어난다. 그때 화면이 로그인 상태를 계속 들고 있지 않도록 알린다.
 */
export function onSessionEnd(listener: (reason: SessionEndReason) => void): () => void {
  sessionEndListeners.add(listener);

  return () => {
    sessionEndListeners.delete(listener);
  };
}

/** 새 Access Token. 세션을 되살리지 못하면 `null`이다 */
export function refreshAccessToken(): Promise<string | null> {
  if (!inFlight) {
    inFlight = withRefreshLock(requestAccessToken).finally(() => {
      inFlight = null;
    });
  }

  return inFlight;
}

/**
 * 다른 탭의 재발급과 겹치지 않게 한다.
 *
 * 잠금을 기다린 탭은 그 사이 갱신된 쿠키로 다시 재발급한다. 회전이 한 번 더 도는 것이지
 * 실패가 아니다. Access Token은 탭의 메모리에만 있어(ADR-0009) 다른 탭의 결과를 그대로
 * 받아 쓸 수 없으므로, 여기서 할 수 있는 것은 순서를 세우는 것까지다.
 */
async function withRefreshLock(run: () => Promise<string | null>): Promise<string | null> {
  // 구형 브라우저와 테스트 환경(jsdom)에는 없다. 타입상으로는 항상 있는 것으로 선언돼 있다
  const locks: LockManager | undefined = navigator.locks;

  if (!locks) {
    return run();
  }

  /*
   * 잠금은 순서를 세우는 편의일 뿐이므로 얻지 못했다고 재발급을 포기하면 안 된다. 잠금을
   * 쥔 탭이 멈추면(중단점, 절전) 기다리는 쪽이 영영 못 돌아오므로 한도를 두고, 한도를
   * 넘기거나 잠금 자체가 실패하면 그냥 보낸다. 겹쳐 나가더라도 서버 유예가 받아 낸다.
   * 잠금 실패를 그대로 던지면 `client`가 기대하는 오류 계약(ApiError)도 깨진다(리뷰 A-6·B-5).
   */
  const limit = new AbortController();
  const timer = setTimeout(() => limit.abort(), LOCK_WAIT_LIMIT_MS);

  // 콜백이 이미 시작했다면 그 안에서 난 오류다. 다시 보내면 재발급이 두 번 나간다
  let started = false;
  const guarded = () => {
    started = true;

    return run();
  };

  try {
    return await locks.request(REFRESH_LOCK, { signal: limit.signal }, guarded);
  } catch (error) {
    if (started) {
      throw error;
    }

    return run();
  } finally {
    clearTimeout(timer);
  }
}

async function requestAccessToken(): Promise<string | null> {
  let response: Response;

  try {
    response = await fetch(REFRESH_URL, {
      method: 'POST',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    });
  } catch {
    /*
     * 요청이 서버에 닿지 못한 것은 세션이 없다는 뜻이 아니다. `refreshable`을 그대로
     * 두어 연결이 돌아왔을 때 다시 시도할 수 있게 한다.
     */
    return null;
  }

  /*
   * 세션이 없다는 신호와 서버가 잠시 응답하지 못한다는 신호를 가른다.
   *
   * 계약이 재발급 거절로 정의한 것은 401뿐이다(API-006 `REFRESH_TOKEN_INVALID`).
   * 5xx는 세션에 대한 판정이 아니라 서버 사정이므로 여기서 세션을 끝내면 안 된다.
   * 끝내 버리면 `refreshable`이 내려가 백엔드가 돌아와도 재발급을 다시 시도하지
   * 않고, 사용자는 새로고침해야 로그인 상태로 돌아온다. 배포 중 재시작 한 번으로
   * 앱을 열어 둔 모두가 로그아웃된 것처럼 보였다(S15P21E106-373).
   */
  if (response.status === UNAUTHORIZED) {
    /*
     * 쓰고 있던 세션이 끊긴 것과 애초에 없던 것을 가른다. 메모리에 Access Token이 있었다면
     * 로그인 상태로 쓰던 중이었다는 뜻이고, 없었다면 이 재발급은 앱이 뜰 때의 복구
     * 시도다(`restoreSession`). 복구 실패는 비로그인일 뿐이라 알릴 만료가 없다.
     */
    endSession(accessToken ? 'expired' : 'unauthenticated');

    return null;
  }

  if (!response.ok) {
    return null;
  }

  /*
   * 본문에 토큰이 없는 200은 계약 위반이지 세션 만료가 아니다. 위와 같은 이유로
   * 세션을 유지하고 이번 재발급만 실패로 둔다.
   */
  const token = await readAccessToken(response);

  if (!token) {
    return null;
  }

  startSession(token);

  return token;
}

async function readAccessToken(response: Response): Promise<string | null> {
  try {
    const body: unknown = await response.json();

    if (body && typeof body === 'object' && 'accessToken' in body) {
      const token = (body as { accessToken: unknown }).accessToken;

      return typeof token === 'string' && token ? token : null;
    }
  } catch {
    return null;
  }

  return null;
}

/** 테스트에서 모듈 상태를 되돌린다. 화면 코드는 쓰지 않는다 */
export function resetSessionForTest(): void {
  accessToken = null;
  refreshable = true;
  inFlight = null;
  sessionEndListeners.clear();
}
