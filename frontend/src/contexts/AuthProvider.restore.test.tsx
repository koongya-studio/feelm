/*
 * 새로고침 복구 → 실패 화면 → 다시 시도 (업무 S15P21E106-908 · !677 리뷰)
 *
 * `AuthProvider.test.tsx`는 `restoreSession`을 가로채 상태 전이만 본다. 여기서는 실제
 * `restoreSession`과 HTTP 클라이언트를 그대로 두고 fetch만 바꿔, 리뷰가 짚은 순서를 끝까지 따라간다.
 *
 * 최초 재발급 성공 → 프로필 503 → 다시 시도 안내 → 다시 시도 → (재발급을 또 보냈다면 503)
 *
 * 다시 시도가 재발급부터 다시 보내면 그 503만으로 살아 있는 세션이 로그인 화면으로 떨어진다.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';
import { AuthProvider } from './AuthProvider';
import { RETRY_DELAYS_MS } from '../api/retry';
import { resetSessionForTest } from '../api/session';
import RequireAuth from '../routes/RequireAuth';
import { mockMyProfile } from '../mocks/user';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const unavailable = () => json(503, { code: 'SERVICE_UNAVAILABLE', message: '점검 중' });

/** 주소별 응답. 테스트가 바꿔 끼운다 */
let reply: { refresh: () => Response; profile: () => Response };

function renderApp() {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/my']} useTransitions={false}>
        <Routes>
          <Route path="/login" element={<p>로그인 화면</p>} />
          <Route element={<RequireAuth />}>
            <Route path="/my" element={<p>MY 화면</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

async function waitRetries() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(RETRY_DELAYS_MS.reduce((sum, ms) => sum + ms, 0));
  });
}

const refreshCalls = () =>
  fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/auth/token/refresh')).length;

beforeEach(() => {
  resetSessionForTest();
  sessionStorage.clear();
  reply = {
    refresh: () => json(200, { accessToken: 'live', expiresIn: 1800 }),
    profile: unavailable,
  };
  fetchMock.mockReset().mockImplementation((url: string) => {
    const path = new URL(url, 'http://localhost').pathname;

    return Promise.resolve(
      path === '/api/v1/auth/token/refresh' ? reply.refresh() : reply.profile(),
    );
  });
  vi.stubGlobal('fetch', fetchMock);
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('복구 실패 화면의 다시 시도', () => {
  it('재발급이 잠깐 실패하는 때에도 로그인 화면으로 떨어지지 않고 프로필만 다시 불러 들어간다', async () => {
    renderApp();
    await waitRetries();

    expect(await screen.findByText('로그인 정보를 불러오지 못했어요')).toBeInTheDocument();
    expect(refreshCalls()).toBe(1);

    // 이제 재발급은 503이다. 다시 시도가 재발급을 보내면 비로그인이 된다
    reply = { refresh: unavailable, profile: () => json(200, mockMyProfile) };
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText('MY 화면')).toBeInTheDocument();
    expect(screen.queryByText('로그인 화면')).toBeNull();
    expect(refreshCalls()).toBe(1);
  });

  it('다시 시도에서도 프로필이 실패하면 다시 시도 안내에 머문다', async () => {
    renderApp();
    await waitRetries();
    await screen.findByText('로그인 정보를 불러오지 못했어요');

    reply = { refresh: unavailable, profile: unavailable };
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitRetries();

    expect(await screen.findByText('로그인 정보를 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.queryByText('로그인 화면')).toBeNull();
  });
});
