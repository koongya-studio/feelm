/*
 * 공통 HTTP 클라이언트.
 *
 * `src/api/`의 도메인 함수는 모두 이 모듈을 거친다. base URL, 인증 헤더, 쿠키 전달,
 * 오류 변환, 만료 토큰 재시도가 한곳에만 있어야 도메인 함수가 계약의 데이터 모양에만
 * 집중할 수 있다(frontend-structure.md 7.1).
 *
 * 절대 URL을 쓰지 않는다. 개발에서는 Vite dev 서버가 `/api`를 백엔드로 넘기고
 * (`vite.config.ts`), 배포에서는 Nginx가 같은 경로를 넘긴다(`infra/nginx/nginx.conf`).
 * 어느 쪽이든 같은 출처라 CORS에 기대지 않아도 되고, 쿠키도 그대로 실린다.
 */
import { ApiError, NETWORK_ERROR, UNEXPECTED_ERROR } from './apiError';
import { canRefresh, getAccessToken, refreshAccessToken } from './session';

/** docs/api/README.md 공통 규약의 Base URL */
const API_BASE = '/api/v1';

/** 계약의 인증 구분 (docs/api/README.md) */
export type AuthMode =
  /** 회원. Access Token 필수 */
  | 'required'
  /** 선택. 토큰이 있으면 실어 보내고 없으면 비회원으로 호출한다 */
  | 'optional'
  /** 공개. 토큰을 싣지 않는다 */
  | 'none';

export type QueryValue = string | number | boolean | undefined | null;

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** JSON 본문. 없으면 `Content-Type`을 붙이지 않는다 */
  body?: unknown;
  /** 질의 문자열. `undefined`·`null` 값은 보내지 않는다 */
  query?: Record<string, QueryValue>;
  /** 기본값은 `required`다. 공개 API는 호출 측이 명시한다 */
  auth?: AuthMode;
};

/**
 * API 요청 한 건.
 *
 * 본문이 없는 응답(`202`·`204`)은 `undefined`로 돌려주므로 그런 API의 반환 타입은
 * `void`로 선언한다.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, auth = 'required' } = options;
  const url = `${API_BASE}${path}${queryString(query)}`;

  const send = (token: string | null) =>
    fetchOrThrow(url, {
      method,
      credentials: 'include',
      headers: headers(body !== undefined, token),
      body: body === undefined ? undefined : JSON.stringify(body),
    });

  let response = await send(auth === 'none' ? null : getAccessToken());

  if (response.status === 401 && auth !== 'none') {
    /*
     * 본문은 한 번만 읽을 수 있으므로 재시도 여부를 정하기 전에 오류로 만들어 둔다.
     *
     * 계약이 재발급을 지시하는 것은 `TOKEN_EXPIRED`지만(공통 규약), 코드를 가리지 않고
     * 401이면 한 번 재시도한다. 새로고침 직후 부팅 재발급이 끝나기 전에 나간 요청은
     * 토큰이 아예 없어 `UNAUTHORIZED`로 돌아오는데, 그것도 재발급하면 살아나는 요청이다.
     * 되살릴 세션이 없으면 `canRefresh()`가 막으므로 반복되지 않는다.
     */
    const failure = await toApiError(response);
    const token = canRefresh() ? await refreshAccessToken() : null;

    if (!token) {
      throw failure;
    }

    response = await send(token);
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  return parse<T>(response);
}

function headers(hasBody: boolean, token: string | null): Record<string, string> {
  const result: Record<string, string> = { Accept: 'application/json' };

  if (hasBody) {
    result['Content-Type'] = 'application/json; charset=utf-8';
  }

  if (token) {
    result.Authorization = `Bearer ${token}`;
  }

  return result;
}

function queryString(query: Record<string, QueryValue> | undefined): string {
  if (!query) {
    return '';
  }

  const params = new URLSearchParams();

  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null) {
      params.set(key, String(value));
    }
  }

  const serialized = params.toString();

  return serialized ? `?${serialized}` : '';
}

async function fetchOrThrow(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch {
    throw new ApiError(
      0,
      NETWORK_ERROR,
      '네트워크에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.',
    );
  }
}

async function parse<T>(response: Response): Promise<T> {
  // 201도 본문을 주므로 상태 코드가 아니라 본문 유무로 가른다
  const text = await response.text();

  if (!text) {
    return undefined as T;
  }

  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(response.status, UNEXPECTED_ERROR, '응답을 이해할 수 없습니다.');
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  const body = await readErrorBody(response);
  const code = typeof body?.code === 'string' ? body.code : UNEXPECTED_ERROR;
  const message =
    typeof body?.message === 'string' && body.message
      ? body.message
      : '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';

  return new ApiError(response.status, code, message, body?.details);
}

/** 규약 형식이 아닌 본문(Nginx 오류 페이지 등)도 오므로 실패해도 던지지 않는다 */
async function readErrorBody(
  response: Response,
): Promise<{ code?: unknown; message?: unknown; details?: unknown } | null> {
  try {
    const body: unknown = await response.json();

    return body && typeof body === 'object' ? (body as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}
