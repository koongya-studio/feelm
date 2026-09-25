/*
 * AuthProvider가 세션 상태를 올리고 내리는지.
 *
 * 기기 푸시 구독을 세션과 맞추던 처리(업무 -583)는 기기 푸시를 걷어내며 뺐다(업무 -859).
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { login, logout, restoreSession } from '../api/auth';
import { endSession } from '../api/session';
import { AuthProvider } from './AuthProvider';
import { useAuth } from './authContext';
import type { AuthenticatedUser } from '../types/auth';

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  restoreSession: vi.fn(),
  signup: vi.fn(),
  socialLogin: vi.fn(),
  socialSignup: vi.fn(),
}));

const USER: AuthenticatedUser = { userId: 7, nickname: '은서', onboardingCompleted: true };

/** 상태를 글자로 보이고, 로그인 · 로그아웃을 버튼으로 부른다 */
function Probe() {
  const auth = useAuth();

  return (
    <>
      <p>{auth.status}</p>
      <button type="button" onClick={() => void auth.signIn('eunseo@feelm.kr', 'p@ssw0rd!')}>
        로그인
      </button>
      <button type="button" onClick={() => void auth.signOut()}>
        로그아웃
      </button>
    </>
  );
}

function renderProvider() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
}

beforeEach(() => {
  vi.mocked(restoreSession).mockReset().mockResolvedValue(null);
  vi.mocked(login).mockReset().mockResolvedValue(USER);
  vi.mocked(logout).mockReset().mockResolvedValue(undefined);
});

describe('AuthProvider · 세션', () => {
  it('세션을 되살리면 로그인 상태로 뜨고, 없으면 비로그인으로 뜬다', async () => {
    vi.mocked(restoreSession).mockResolvedValueOnce(USER);
    const { unmount } = renderProvider();

    expect(await screen.findByText('authenticated')).toBeInTheDocument();
    unmount();

    renderProvider();

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
  });

  it('로그인하면 로그인 상태가 되고, 로그아웃하면 서버에 알린 뒤 비로그인이 된다', async () => {
    renderProvider();
    await screen.findByText('anonymous');

    fireEvent.click(screen.getByRole('button', { name: '로그인' }));
    expect(await screen.findByText('authenticated')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));

    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('세션이 만료되면 비로그인 상태로 돌아간다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(USER);
    renderProvider();
    await screen.findByText('authenticated');

    act(() => endSession('expired'));

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
  });
});
