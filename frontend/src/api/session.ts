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

const sessionEndListeners = new Set<() => void>();

export function getAccessToken(): string | null {
  return accessToken;
}

/** 로그인·재발급이 성공했을 때 */
export function startSession(token: string): void {
  accessToken = token;
  refreshable = true;
}

/** 로그아웃했거나 재발급이 거절당했을 때 */
export function endSession(): void {
  accessToken = null;
  refreshable = false;

  for (const listener of sessionEndListeners) {
    listener();
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
export function onSessionEnd(listener: () => void): () => void {
  sessionEndListeners.add(listener);

  return () => {
    sessionEndListeners.delete(listener);
  };
}

/** 새 Access Token. 세션을 되살리지 못하면 `null`이다 */
export function refreshAccessToken(): Promise<string | null> {
  if (!inFlight) {
    inFlight = requestAccessToken().finally(() => {
      inFlight = null;
    });
  }

  return inFlight;
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
    endSession();

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
