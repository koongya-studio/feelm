/*
 * 목업 응답 전송 계층.
 *
 * 이번 단계는 실제 API를 호출하지 않는다. 화면은 src/api/의 함수만 호출하고
 * 실제 연동 단계에서는 그 함수 본문을 교체한다(frontend-structure.md 7.1).
 *
 * 로딩 · 빈 데이터 · 오류 상태 UI 업무(S15P21E106-264)가 지연과 실패를 주입할 수
 * 있도록 여기에 지점을 하나만 둔다.
 */

/** 목업 시나리오. 화면 확인용이며 실제 연동 시 제거한다 */
export type MockScenario = 'success' | 'error';

export class ApiError extends Error {
  status: number;
  /** 계약 공통 규약의 오류 코드 (docs/api/README.md) */
  code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

/** 목업 응답 지연(ms). 업무 16에서 로딩 상태를 확인할 때 값을 올린다 */
const MOCK_DELAY_MS = 0;

function wait(delayMs: number): Promise<void> {
  if (delayMs <= 0) {
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    setTimeout(resolve, delayMs);
  });
}

export async function respond<T>(data: T): Promise<T> {
  await wait(MOCK_DELAY_MS);

  return data;
}

export async function respondError(status: number, code: string, message: string): Promise<never> {
  await wait(MOCK_DELAY_MS);

  throw new ApiError(status, code, message);
}
