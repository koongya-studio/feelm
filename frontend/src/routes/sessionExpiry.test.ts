/*
 * 세션 만료 시의 이동 (업무 S15P21E106-596).
 *
 * 2026-09-17 여정 QA D-3은 재발급이 401로 거절된 뒤에도 화면에 그대로 남아 장애 문구만
 * 반복되는 상태였다. 여기서 고정하는 것은 "언제 로그인 화면으로 보내는가" 하나다.
 * 세션이 없다는 판정 자체는 `api/session`이 한다(업무 -373).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endSession, refreshAccessToken, resetSessionForTest, startSession } from '../api/session';
import { redirectOnSessionExpiry } from './sessionExpiry';

const fetchMock = vi.fn();

beforeEach(() => {
  resetSessionForTest();
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('세션 만료 이동', () => {
  it('재발급이 거절되면 로그인 화면으로 보내고 만료를 알린다', async () => {
    const navigate = vi.fn();
    redirectOnSessionExpiry(navigate);
    startSession('old');
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ code: 'REFRESH_TOKEN_INVALID' }), { status: 401 }),
    );

    await refreshAccessToken();

    expect(navigate).toHaveBeenCalledWith('/login', {
      replace: true,
      state: { sessionExpired: true },
    });
  });

  /*
   * 앱이 뜰 때의 복구 재발급은 비로그인 방문자에게도 나간다. 이것을 만료로 다루면 공개
   * 화면 전체에 사실상 라우트 보호가 걸리고, 소셜 콜백과 이메일 인증 링크가 로그인 화면으로
   * 대체된다(리뷰 A-1·B-1).
   */
  it('로그인한 적 없는 방문자의 복구 재발급 거절은 이동시키지 않는다', async () => {
    const navigate = vi.fn();
    redirectOnSessionExpiry(navigate);
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ code: 'REFRESH_TOKEN_INVALID' }), { status: 401 }),
    );

    await refreshAccessToken();

    expect(navigate).not.toHaveBeenCalled();
  });

  it('서버 장애로 재발급에 실패한 것은 만료가 아니므로 화면에 머문다', async () => {
    const navigate = vi.fn();
    redirectOnSessionExpiry(navigate);
    startSession('old');
    fetchMock.mockResolvedValue(new Response(null, { status: 503 }));

    await refreshAccessToken();

    expect(navigate).not.toHaveBeenCalled();
  });

  it('사용자가 로그아웃한 경우에는 이 배선이 이동시키지 않는다', () => {
    const navigate = vi.fn();
    redirectOnSessionExpiry(navigate);
    startSession('old');

    endSession('signed-out');

    expect(navigate).not.toHaveBeenCalled();
  });

  it('구독을 끊으면 더 이상 이동하지 않는다', () => {
    const navigate = vi.fn();
    const unsubscribe = redirectOnSessionExpiry(navigate);

    unsubscribe();
    endSession('expired');

    expect(navigate).not.toHaveBeenCalled();
  });
});
