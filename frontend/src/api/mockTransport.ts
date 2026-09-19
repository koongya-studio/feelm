/*
 * 목업 응답 전송 계층.
 *
 * 이번 단계는 실제 API를 호출하지 않는다. 화면은 src/api/의 함수만 호출하고
 * 실제 연동 단계에서는 그 함수 본문을 교체한다(frontend-structure.md 7.1).
 *
 * 지연과 실패는 URL 쿼리로 주입한다(frontend-structure.md 7.4가 요구한 주입 지점).
 * **목업 확인용 입력이며 실제 연동 시 이 파일과 함께 제거한다.**
 *
 * | 입력 | 뜻 |
 * | --- | --- |
 * | `?mockDelay=800` | 모든 목업 응답을 800ms 늦춘다 (최대 5000) |
 * | `?mockError=parties` | 그 도메인 함수만 실패한다. 쉼표로 여러 개 |
 * | `?mockError=all` | 전체 실패 |
 *
 * 지연을 올려도 로딩 표시는 나오지 않는다. 데이터를 받기 전에는 아무것도 그리지 않기로
 * 정했다(팀 결정 2026-09-08). 지연은 그 동작을 확인하고 오류 주입과 함께 쓰기 위한 것이다.
 */

import { ApiError } from './apiError';

/** 오류를 주입할 수 있는 도메인. src/api/ 함수가 `respond`에 넘긴다 */
export type MockDomain =
  | 'recommendations'
  | 'taste'
  | 'frames'
  | 'popcorn'
  | 'parties'
  | 'users'
  | 'movies'
  | 'otts'
  | 'onboarding'
  | 'watch'
  | 'reports';

/*
 * 오류 타입은 `apiError`에 있다. 목업이 사라져도 화면과 HTTP 클라이언트가 계속 쓰므로
 * 이 파일과 수명을 같이 하면 안 된다. 기존 import 경로를 지키려고 다시 export한다.
 */
export { ApiError };

/** 지연 상한. 실수로 큰 값을 넣어도 화면이 멈춘 것처럼 보이지 않게 한다 */
const MAX_MOCK_DELAY_MS = 5000;

function mockQuery(): URLSearchParams {
  if (typeof window === 'undefined') {
    return new URLSearchParams();
  }

  return new URLSearchParams(window.location.search);
}

function mockDelayMs(): number {
  const requested = Number(mockQuery().get('mockDelay'));

  if (!Number.isFinite(requested) || requested <= 0) {
    return 0;
  }

  return Math.min(requested, MAX_MOCK_DELAY_MS);
}

/** 이 도메인에 오류가 주입됐는지. 계약 고유 오류 코드를 쓰는 함수가 직접 확인한다 */
export function isMockError(domain: MockDomain): boolean {
  const query = mockQuery();
  const requested = (query.get('mockError') ?? '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean);

  return requested.includes('all') || requested.includes(domain);
}

/**
 * 목업 응답.
 *
 * `domain`을 넘기면 그 도메인에 오류가 주입됐을 때 실패한다. 계약에 고유 오류 코드가 있는
 * 함수는 `domain`을 넘기지 않고 `isMockError`로 직접 확인해 그 코드를 쓴다.
 */
export async function respond<T>(data: T, domain?: MockDomain): Promise<T> {
  await wait(mockDelayMs());

  if (domain && isMockError(domain)) {
    /*
     * 공통 규약의 5xx 하나만 쓴다. 화면은 네트워크 단절(`NETWORK_ERROR`)과 서버 실패만
     * 가르므로(`components/StatusView/failure.ts` · 업무 -374) 목업의 실패는 모두 서버
     * 실패로 보인다. 네트워크 단절은 실제 연동 화면에서 백엔드를 내려 확인한다.
     */
    throw new ApiError(503, 'SERVICE_UNAVAILABLE', '일시적으로 요청을 처리할 수 없습니다.');
  }

  return data;
}

export async function respondError(status: number, code: string, message: string): Promise<never> {
  await wait(mockDelayMs());

  throw new ApiError(status, code, message);
}

function wait(delayMs: number): Promise<void> {
  if (delayMs <= 0) {
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    setTimeout(resolve, delayMs);
  });
}
