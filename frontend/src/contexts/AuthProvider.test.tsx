/*
 * AuthProvider가 브라우저 푸시 구독을 세션과 맞추는지(업무 S15P21E106-583).
 *
 * 구독 자체(브라우저 · 서버 호출)는 `pwa/webPush.test.ts`가 본다. 여기서는 언제 · 어떤 순서로
 * 부르는지만 본다.
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { login, logout, restoreSession } from '../api/auth';
import { endSession } from '../api/session';
import { forgetWebPush, releaseWebPush, syncWebPush } from '../pwa/webPush';
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

vi.mock('../pwa/webPush', () => ({
  syncWebPush: vi.fn(async () => undefined),
  releaseWebPush: vi.fn(async () => undefined),
  forgetWebPush: vi.fn(async () => undefined),
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
  vi.mocked(syncWebPush).mockClear();
  vi.mocked(releaseWebPush).mockClear();
  vi.mocked(forgetWebPush).mockClear();
});

describe('AuthProvider · 브라우저 푸시', () => {
  it('세션을 되살리면 이 기기의 구독을 한 번 다시 올린다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(USER);

    const { rerender } = renderProvider();

    expect(await screen.findByText('authenticated')).toBeInTheDocument();
    await waitFor(() => expect(syncWebPush).toHaveBeenCalledTimes(1));
    rerender(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );

    // 다시 그려도 같은 사용자면 또 올리지 않는다
    expect(syncWebPush).toHaveBeenCalledTimes(1);
  });

  it('로그인하지 않은 채로 뜨면 올리지 않는다', async () => {
    renderProvider();

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
    expect(syncWebPush).not.toHaveBeenCalled();
  });

  it('로그인하면 그때 한 번 올린다', async () => {
    renderProvider();
    await screen.findByText('anonymous');

    fireEvent.click(screen.getByRole('button', { name: '로그인' }));

    expect(await screen.findByText('authenticated')).toBeInTheDocument();
    // 구독 재등록은 화면이 그려진 뒤의 effect다. 그려진 것만 보고 바로 세면 흔들린다
    await waitFor(() => expect(syncWebPush).toHaveBeenCalledTimes(1));
  });

  it('로그아웃하면 인증이 살아 있을 때 구독을 먼저 해제하고 로그아웃한다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(USER);
    renderProvider();
    await screen.findByText('authenticated');

    fireEvent.click(screen.getByRole('button', { name: '로그아웃' }));

    await waitFor(() => expect(screen.getByText('anonymous')).toBeInTheDocument());
    expect(releaseWebPush).toHaveBeenCalledTimes(1);
    expect(logout).toHaveBeenCalledTimes(1);
    expect(vi.mocked(releaseWebPush).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(logout).mock.invocationCallOrder[0],
    );
  });

  it('세션이 만료돼 로그아웃 상태가 되면 이 기기의 푸시 알림도 끈다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(USER);
    renderProvider();
    await screen.findByText('authenticated');

    act(() => endSession('expired'));

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
    // 토큰이 없어 서버 해제는 보낼 수 없다. 기기 등록만 지운다
    expect(forgetWebPush).toHaveBeenCalledTimes(1);
    expect(releaseWebPush).not.toHaveBeenCalled();
  });

  it('누른 로그아웃은 서버까지 해제하므로 기기만 지우는 처리를 또 하지 않는다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(USER);
    renderProvider();
    await screen.findByText('authenticated');

    act(() => endSession('signed-out'));

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
    expect(forgetWebPush).not.toHaveBeenCalled();
  });
});
