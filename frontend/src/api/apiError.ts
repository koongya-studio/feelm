/*
 * 계약 공통 오류 표현.
 *
 * `mockTransport`에 있던 것을 옮겼다. 실제 연동이 끝나면 목업 전송 계층은 사라지는데
 * (frontend-structure.md 7.1), 화면과 HTTP 클라이언트가 모두 이 타입으로 오류를
 * 구분하므로 목업과 수명을 같이 하면 안 된다. `mockTransport`는 아직 목업으로 남은
 * 도메인을 위해 이 파일을 다시 export한다.
 */

/** 오류 응답 본문. 모든 API가 같은 구조를 쓴다 (docs/api/README.md 공통 규약) */
export type ErrorBody = {
  /** UPPER_SNAKE_CASE 의미 코드. 화면 분기의 기준이다 */
  code: string;
  /** 사용자에게 그대로 보여줄 수 있는 한국어 문구 */
  message: string;
  /** 검증 실패 필드 등 부가 정보 */
  details?: unknown;
};

export class ApiError extends Error {
  status: number;
  /** 계약 공통 규약의 오류 코드 (docs/api/README.md) */
  code: string;
  details?: unknown;

  constructor(status: number, code: string, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

/**
 * 요청이 서버에 닿지 못했을 때 쓰는 코드.
 *
 * 서버가 주는 코드와 겹치지 않아야 화면이 "응답을 받았는데 실패"와 "응답 자체가 없음"을
 * 구분할 수 있다. 상태 코드는 0으로 둔다.
 */
export const NETWORK_ERROR = 'NETWORK_ERROR';

/** 규약 형식이 아닌 본문(프록시 오류 페이지 등)을 받았을 때 쓰는 코드 */
export const UNEXPECTED_ERROR = 'UNEXPECTED_ERROR';

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}
