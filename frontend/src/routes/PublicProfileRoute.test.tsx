import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { router } from './router';
import { AuthContext } from '../contexts/authContext';
import { SessionRestoreContext } from '../contexts/sessionRestoreContext';
import { getPublicProfile } from '../api/users';
import { mockMyProfileView } from '../mocks/publicProfiles';
import { ApiError } from '../api/apiError';
import type { AuthStatus, AuthValue } from '../contexts/authContext';

vi.mock('../api/users', async (original) => ({
  ...(await original<typeof import('../api/users')>()),
  getPublicProfile: vi.fn(),
}));
const id = '1b4e28ba-2fa1-11d2-883f-0016d3cca427';
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});
function open(route: string, status: AuthStatus = 'anonymous', failed = false) {
  const memory = createMemoryRouter(router.routes, { initialEntries: [route] });
  const auth: AuthValue = {
    status,
    user: null,
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeEmailSignup: vi.fn(),
    completeSocialSignup: vi.fn(),
    signOut: vi.fn(),
  };
  const retry = vi.fn();
  const view = render(
    <AuthContext.Provider value={auth}>
      <SessionRestoreContext.Provider value={{ failed, retry }}>
        <RouterProvider router={memory} />
      </SessionRestoreContext.Provider>
    </AuthContext.Provider>,
  );
  return Object.assign(memory, { retry, view, auth });
}
describe('실제 공개 프로필 라우팅', () => {
  it('비회원도 공유 UUID로 공개 프로필을 조회한다', async () => {
    vi.mocked(getPublicProfile).mockResolvedValue(mockMyProfileView);
    const memory = open('/users/' + id);
    expect(await screen.findByText(mockMyProfileView.nickname)).toBeInTheDocument();
    expect(getPublicProfile).toHaveBeenCalledWith(id);
    expect(memory.state.location.pathname).toBe('/users/' + id);
  });
  it('비공개 또는 차단 404는 로그인 이동 대신 조회 불가 안내다', async () => {
    vi.mocked(getPublicProfile).mockRejectedValue(new ApiError(404, 'RESOURCE_NOT_FOUND', '없음'));
    const memory = open('/users/' + id);
    expect(await screen.findByText('프로필을 볼 수 없어요')).toBeInTheDocument();
    expect(memory.state.location.pathname).toBe('/users/' + id);
  });
  it('복구 중에는 비회원으로 먼저 조회하지 않는다', () => {
    open('/users/' + id, 'restoring');
    expect(getPublicProfile).not.toHaveBeenCalled();
  });
  it('복구 후 로그인 상태로 조회하고 차단 404를 유지한다', async () => {
    vi.mocked(getPublicProfile).mockRejectedValue(new ApiError(404, 'RESOURCE_NOT_FOUND', '없음'));
    const memory = open('/users/' + id, 'restoring');
    expect(getPublicProfile).not.toHaveBeenCalled();
    memory.view.rerender(
      <AuthContext.Provider value={{ ...memory.auth, status: 'authenticated' }}>
        <SessionRestoreContext.Provider value={{ failed: false, retry: memory.retry }}>
          <RouterProvider router={memory} />
        </SessionRestoreContext.Provider>
      </AuthContext.Provider>,
    );
    expect(await screen.findByText('프로필을 볼 수 없어요')).toBeInTheDocument();
    expect(getPublicProfile).toHaveBeenCalledExactlyOnceWith(id);
    expect(memory.state.location.pathname).toBe('/users/' + id);
  });
  it('복구 실패는 재시도 안내를 제공한다', async () => {
    const memory = open('/users/' + id, 'restoring', true);
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));
    expect(memory.retry).toHaveBeenCalledOnce();
    expect(getPublicProfile).not.toHaveBeenCalled();
  });
  it('내 공개 프로필 미리보기는 여전히 로그인이 필요하다', async () => {
    const memory = open('/my/profile/preview');
    await vi.waitFor(() => expect(memory.state.location.pathname).toBe('/login'));
    expect(getPublicProfile).not.toHaveBeenCalled();
  });
});
