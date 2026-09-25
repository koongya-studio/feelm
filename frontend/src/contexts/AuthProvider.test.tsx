/*
 * AuthProvider가 세션 상태를 올리고 내리는지.
 *
 * 기기 푸시 구독을 세션과 맞추던 처리(업무 -583)는 기기 푸시를 걷어내며 뺐다(업무 -859).
 */
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { login, logout, restoreSession } from '../api/auth';
import { ApiError, NETWORK_ERROR } from '../api/apiError';
import { endSession, resetSessionForTest } from '../api/session';
import { AuthProvider } from './AuthProvider';
import { useAuth } from './authContext';
import { useSessionRestore } from './sessionRestoreContext';
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
  const restore = useSessionRestore();

  return (
    <>
      <p>{auth.status}</p>
      {restore.failed ? (
        <button type="button" onClick={restore.retry}>
          복구 다시 시도
        </button>
      ) : null}
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
  resetSessionForTest();
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

/*
 * 업무 -908. 재발급은 됐는데 프로필을 서버 · 네트워크 사정으로 끝내 못 받으면 세션은 살아
 * 있다. 비로그인으로 떨어뜨리면 새로고침만으로 로그인 화면에 튕긴다.
 */
describe('AuthProvider · 복구 일시 실패', () => {
  const unavailable = () => new ApiError(503, 'SERVICE_UNAVAILABLE', '점검 중');

  it('일시 오류로 끝나면 비로그인이 아니라 복구 중에 머물고 다시 시도를 내려 준다', async () => {
    vi.mocked(restoreSession).mockRejectedValueOnce(unavailable());
    renderProvider();

    expect(await screen.findByRole('button', { name: '복구 다시 시도' })).toBeInTheDocument();
    expect(screen.getByText('restoring')).toBeInTheDocument();
    expect(screen.queryByText('anonymous')).toBeNull();
  });

  it('다시 시도하면 복구를 처음부터 다시 하고, 되면 로그인 상태가 된다', async () => {
    vi.mocked(restoreSession)
      .mockRejectedValueOnce(new ApiError(0, NETWORK_ERROR, '연결 실패'))
      .mockResolvedValueOnce(USER);
    renderProvider();

    fireEvent.click(await screen.findByRole('button', { name: '복구 다시 시도' }));

    expect(await screen.findByText('authenticated')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '복구 다시 시도' })).toBeNull();
    expect(restoreSession).toHaveBeenCalledTimes(2);
  });

  it('재발급이 거절돼 세션이 끝났으면 지금처럼 비로그인이다', async () => {
    // 복구 도중 재발급이 거절되면 세션이 끝나 canRefresh()가 내려간다
    vi.mocked(restoreSession).mockImplementationOnce(async () => {
      endSession('expired');
      throw new ApiError(401, 'TOKEN_EXPIRED', '만료');
    });
    renderProvider();

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '복구 다시 시도' })).toBeNull();
  });

  it('영구 오류는 비로그인과 같게 다룬다', async () => {
    vi.mocked(restoreSession).mockRejectedValueOnce(new ApiError(404, 'USER_NOT_FOUND', '없음'));
    renderProvider();

    expect(await screen.findByText('anonymous')).toBeInTheDocument();
  });
});
