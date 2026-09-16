/*
 * 앱 진입점 `/`의 갈림길 (업무 S15P21E106-319)
 *
 * 앱을 열면 스플래시 뒤에 로그인 전이면 시작 화면, 로그인 후면 홈이 나와야 한다. 세션을
 * 되살리는 동안 홈을 먼저 그리면 시작 화면으로 튕기며 화면이 갈리므로 그것도 고정한다.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import HomeEntry from './HomeEntry';
import { AuthContext } from '../contexts/authContext';
import type { AuthStatus, AuthValue } from '../contexts/authContext';

vi.mock('../pages/home/HomePage', () => ({ default: () => <p>홈 화면</p> }));

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

function renderEntry(status: AuthStatus) {
  const value: AuthValue = {
    status,
    user: null,
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeSocialSignup: vi.fn(),
    signOut: vi.fn(),
  };

  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<HomeEntry />} />
          <Route path="/start" element={<p>시작 화면</p>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('앱 진입점', () => {
  it('로그인 전이면 시작 화면으로 보낸다', () => {
    renderEntry('anonymous');

    expect(screen.getByText('시작 화면')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/start');
  });

  it('로그인했으면 홈을 그린다', () => {
    renderEntry('authenticated');

    expect(screen.getByText('홈 화면')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/');
  });

  it('세션을 되살리는 동안에는 아무것도 그리지 않는다', () => {
    renderEntry('restoring');

    expect(screen.queryByText('홈 화면')).toBeNull();
    expect(screen.queryByText('시작 화면')).toBeNull();
    expect(screen.getByTestId('location').textContent).toBe('/');
  });
});
