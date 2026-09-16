/*
 * 비밀번호 재설정·변경 요청 모양 (업무 S15P21E106-391).
 *
 * 세 API가 비밀번호를 담는 필드 이름이 서로 다르다 — 재설정은 `newPassword`, 변경은
 * `currentPassword` · `newPassword`, 로그인은 `password`다. 이름을 틀리면 서버는
 * `400 VALIDATION_FAILED`로만 답해 화면에서는 "정책 위반"과 구분되지 않는다. 화면
 * 테스트는 함수가 불렸는지만 보므로 본문은 여기서 고정한다.
 *
 * 인증 구분도 함께 본다. API-010 · API-011은 공개라 토큰을 실으면 안 되고(메일 링크는
 * 로그인하지 않은 브라우저에서 열린다), API-012는 회원 전용이다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { changePassword, requestPasswordReset, resetPassword } from './auth';
import { resetSessionForTest, startSession } from './session';

const fetchMock = vi.fn();

function call(index = 0) {
  return fetchMock.mock.calls[index] as [string, RequestInit];
}

function body(index = 0): unknown {
  return JSON.parse(String(call(index)[1].body));
}

function authorization(index = 0) {
  return (call(index)[1].headers as Record<string, string>).Authorization;
}

beforeEach(() => {
  resetSessionForTest();
  startSession('access-token');
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-010 비밀번호 재설정 요청', () => {
  it('이메일만 담아 공개로 보낸다', async () => {
    await requestPasswordReset('user@example.com');

    expect(call()[0]).toBe('/api/v1/auth/password/reset-request');
    expect(call()[1].method).toBe('POST');
    expect(body()).toEqual({ email: 'user@example.com' });
    expect(authorization()).toBeUndefined();
  });
});

describe('API-011 비밀번호 재설정', () => {
  it('토큰과 새 비밀번호를 계약의 이름으로 담아 공개로 보낸다', async () => {
    await resetPassword('opaque-token', 'n3wP@ss!');

    expect(call()[0]).toBe('/api/v1/auth/password/reset');
    expect(body()).toEqual({ token: 'opaque-token', newPassword: 'n3wP@ss!' });
    expect(authorization()).toBeUndefined();
  });
});

describe('API-012 비밀번호 변경', () => {
  it('현재·새 비밀번호를 담아 회원으로 PUT 한다', async () => {
    await changePassword('old1Pass!', 'n3wP@ss!');

    expect(call()[0]).toBe('/api/v1/users/me/password');
    expect(call()[1].method).toBe('PUT');
    expect(body()).toEqual({ currentPassword: 'old1Pass!', newPassword: 'n3wP@ss!' });
    expect(authorization()).toBe('Bearer access-token');
  });
});
