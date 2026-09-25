/*
 * 보호 라우트 (업무 S15P21E106-610)
 *
 * 비로그인이면 로그인으로 보내되 가려던 주소를 남기고, 세션을 되살리는 동안은 보호
 * 화면을 스치지 않게 하며, 로그인했으면 그대로 연다.
 */
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import RequireAuth from './RequireAuth';
import { isReturnPath } from './returnPath';
import { AuthContext } from '../contexts/authContext';
import { SessionRestoreContext } from '../contexts/sessionRestoreContext';
import type { AuthStatus, AuthValue } from '../contexts/authContext';

function LocationProbe() {
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '';

  return (
    <div data-testid="location">
      {location.pathname}
      {location.search}|{from}
    </div>
  );
}

function renderGuarded(
  status: AuthStatus,
  route = '/my',
  restore = { failed: false, retry: vi.fn() },
) {
  const value: AuthValue = {
    status,
    user: null,
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeEmailSignup: vi.fn(),
    completeSocialSignup: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <SessionRestoreContext.Provider value={restore}>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path="/login" element={<p>로그인 화면</p>} />
            <Route element={<RequireAuth />}>
              <Route path="/my" element={<p>MY 화면</p>} />
              <Route path="/parties/:partyId/popcorn" element={<p>파티 화면</p>} />
            </Route>
          </Routes>
          <LocationProbe />
        </MemoryRouter>
      </SessionRestoreContext.Provider>
    </AuthContext.Provider>,
  );
}

describe('보호 라우트', () => {
  it('비로그인이면 로그인으로 보내고 가려던 주소를 남긴다', () => {
    renderGuarded('anonymous', '/parties/9001/popcorn?tab=all');

    expect(screen.getByText('로그인 화면')).toBeInTheDocument();
    expect(screen.queryByText('파티 화면')).toBeNull();
    expect(screen.getByTestId('location').textContent).toBe('/login|/parties/9001/popcorn?tab=all');
  });

  /*
   * 업무 -908. 재발급은 됐는데 프로필을 서버 · 네트워크 사정으로 못 받았다. 세션은 살아 있어
   * 로그인 화면으로 보내면 안 된다.
   */
  it('복구가 일시 오류로 끝났으면 로그인으로 보내지 않고 다시 시도를 보인다', () => {
    const retry = vi.fn();
    renderGuarded('restoring', '/my', { failed: true, retry });

    expect(screen.getByText('로그인 정보를 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.queryByText('로그인 화면')).toBeNull();
    expect(screen.queryByText('MY 화면')).toBeNull();
    expect(screen.getByTestId('location').textContent).toBe('/my|');

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('세션을 되살리는 동안에는 보호 화면도 로그인 화면도 그리지 않는다', () => {
    renderGuarded('restoring');

    expect(screen.queryByText('MY 화면')).toBeNull();
    expect(screen.queryByText('로그인 화면')).toBeNull();
    expect(screen.getByTestId('location').textContent).toBe('/my|');
  });

  it('로그인했으면 그대로 연다', () => {
    renderGuarded('authenticated');

    expect(screen.getByText('MY 화면')).toBeInTheDocument();
  });
});

describe('돌아갈 주소 판정', () => {
  it('앱 안의 경로만 받는다', () => {
    expect(isReturnPath('/my?tab=unrated')).toBe(true);
    expect(isReturnPath('//evil.example/steal')).toBe(false);
    expect(isReturnPath('https://evil.example')).toBe(false);
    expect(isReturnPath(undefined)).toBe(false);
  });
});
