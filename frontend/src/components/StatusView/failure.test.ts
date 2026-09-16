/*
 * 실패 원인 → 보조 문구 (업무 S15P21E106-374)
 *
 * 화면이 실패를 알릴 때 네트워크와 서버를 가르는 기준은 이 함수 하나다. 여기가 어긋나면
 * 모든 화면이 같은 방향으로 틀린 안내를 하므로 경계값을 고정한다.
 */
import { describe, expect, it } from 'vitest';
import { ApiError, NETWORK_ERROR, UNEXPECTED_ERROR } from '../../api/apiError';
import {
  NETWORK_FAILURE_DESCRIPTION,
  SERVER_FAILURE_DESCRIPTION,
  failureDescription,
} from './failure';

describe('실패 보조 문구', () => {
  it('서버에 닿지 못했으면 연결을 확인하라고 한다', () => {
    const error = new ApiError(0, NETWORK_ERROR, '네트워크에 연결할 수 없습니다.');

    expect(failureDescription(error)).toBe(NETWORK_FAILURE_DESCRIPTION);
  });

  it('5xx는 서버 문구를 그대로 쓰지 않고 기다리라고 한다', () => {
    expect(failureDescription(new ApiError(500, 'INTERNAL_ERROR', '서버 오류'))).toBe(
      SERVER_FAILURE_DESCRIPTION,
    );
    expect(
      failureDescription(new ApiError(503, 'SERVICE_UNAVAILABLE', '일시적으로 처리할 수 없음')),
    ).toBe(SERVER_FAILURE_DESCRIPTION);
  });

  it('규약 밖 본문(프록시 오류 페이지)은 서버 실패다', () => {
    expect(failureDescription(new ApiError(502, UNEXPECTED_ERROR, '응답을 이해할 수 없음'))).toBe(
      SERVER_FAILURE_DESCRIPTION,
    );
  });

  it('화면까지 온 401은 재발급이 안 된 것이라 서버 실패로 본다', () => {
    expect(failureDescription(new ApiError(401, 'TOKEN_EXPIRED', '토큰이 만료되었습니다.'))).toBe(
      SERVER_FAILURE_DESCRIPTION,
    );
  });

  it('그 밖의 4xx는 서버가 준 사유를 그대로 보여준다', () => {
    expect(
      failureDescription(new ApiError(429, 'RATE_LIMITED', '요청이 너무 많습니다. 잠시 후 다시.')),
    ).toBe('요청이 너무 많습니다. 잠시 후 다시.');
    expect(failureDescription(new ApiError(404, 'RESOURCE_NOT_FOUND', '찾을 수 없습니다.'))).toBe(
      '찾을 수 없습니다.',
    );
  });

  it('계약 밖의 예외는 서버 실패 문구로 덮는다', () => {
    expect(failureDescription(new TypeError('x is not a function'))).toBe(
      SERVER_FAILURE_DESCRIPTION,
    );
    expect(failureDescription(undefined)).toBe(SERVER_FAILURE_DESCRIPTION);
  });
});
