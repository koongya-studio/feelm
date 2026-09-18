/*
 * 7.8-A-2 비밀번호 변경 (업무 S15P21E106-391)
 *
 * 고정하는 것은 두 가지다.
 *
 * 하나는 **성공한 뒤 세션을 끊는 것**이다. 서버가 Refresh Token을 모두 폐기하므로
 * (API-012 처리 규칙) 화면이 로그인 상태를 계속 들고 있으면 Access Token이 만료되는
 * 순간 아무 화면에서나 갑자기 로그아웃된다. 성공 응답만 보고는 드러나지 않는 자리다.
 *
 * 다른 하나는 **현재 비밀번호 불일치를 다른 실패와 가르는 것**이다. 계약이 `401
 * INVALID_CREDENTIALS`로 따로 주는 이유가 그것이고, 뭉뚱그리면 사용자는 어느 칸을
 * 고쳐야 하는지 알 수 없다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import PasswordChangePage from './index';
import { ApiError } from '../../../api/apiError';
import { changePassword } from '../../../api/auth';
import { AuthContext } from '../../../contexts/authContext';
import type { AuthValue } from '../../../contexts/authContext';

vi.mock('../../../api/auth', () => ({ changePassword: vi.fn() }));

/** 옛 규칙(8자·영문·숫자)과 계약(8~64자·영문·숫자·특수문자)을 모두 만족한다 */
const NEW_PASSWORD = 'n3wP@ss!';

const signOut = vi.fn<AuthValue['signOut']>();

beforeEach(() => {
  vi.mocked(changePassword).mockReset().mockResolvedValue(undefined);
  signOut.mockReset().mockResolvedValue(undefined);
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderPage() {
  const auth: AuthValue = {
    status: 'authenticated',
    user: { userId: 1024, nickname: '김난다', onboardingCompleted: true },
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeEmailSignup: vi.fn(),
    completeSocialSignup: vi.fn(),
    signOut,
  };

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/my/settings/account/password']}>
        <PasswordChangePage />
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

function fill(current: string, password: string, confirmation = password) {
  fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: current } });
  fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value: confirmation } });
  fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
}

const location = () => screen.getByTestId('location').textContent;

describe('7.8-A-2 비밀번호 변경', () => {
  it('현재 비밀번호와 새 비밀번호를 API-012로 보낸다', async () => {
    renderPage();

    fill('old1Pass!', NEW_PASSWORD);

    await waitFor(() => expect(changePassword).toHaveBeenCalledWith('old1Pass!', NEW_PASSWORD));
  });

  it('성공하면 세션을 끊고 로그인 화면으로 보낸다', async () => {
    renderPage();

    fill('old1Pass!', NEW_PASSWORD);

    await waitFor(() => expect(signOut).toHaveBeenCalled());
    expect(location()).toBe('/login');
  });

  it('현재 비밀번호가 틀리면 그 사실을 알리고 세션을 끊지 않는다', async () => {
    vi.mocked(changePassword).mockRejectedValue(
      new ApiError(401, 'INVALID_CREDENTIALS', '인증에 실패했습니다.'),
    );

    renderPage();

    fill('wrong1Pass!', NEW_PASSWORD);

    expect(await screen.findByRole('alert')).toHaveTextContent('현재 비밀번호가 올바르지 않아요');
    expect(signOut).not.toHaveBeenCalled();
    expect(location()).toBe('/my/settings/account/password');
  });

  it('소셜 전용 계정은 바꿀 비밀번호가 없다고 알린다', async () => {
    vi.mocked(changePassword).mockRejectedValue(
      new ApiError(409, 'PASSWORD_NOT_SET', '비밀번호가 설정되지 않은 계정입니다.'),
    );

    renderPage();

    fill('old1Pass!', NEW_PASSWORD);

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호가 없어요');
  });

  it('두 값이 다르면 보내지 않는다', () => {
    renderPage();

    fill('old1Pass!', NEW_PASSWORD, 'other9Pass!');

    expect(screen.getByRole('alert')).toHaveTextContent('비밀번호가 서로 달라요');
    expect(changePassword).not.toHaveBeenCalled();
  });
});
