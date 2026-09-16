/*
 * 0.3-A 소셜 가입 정보 입력 — 계약 필드와 실패 안내 (업무 S15P21E106-499)
 *
 * 와이어프레임에 없이 만든 화면이라(CR-23) 무엇을 보내는지 고정해 둔다. 이메일과 제공자
 * 식별자는 `signupToken` 안에 있어 본문에 넣지 않는 것이 계약이다(API-008 처리 규칙).
 *
 * 실패는 사용자가 할 수 있는 일이 서로 다르다. 토큰 만료는 소셜 로그인부터 다시 해야
 * 하고, 이메일 중복은 이메일로 로그인한 뒤 계정을 연결해야 한다(API-009).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import SocialSignupProfilePage from './index';
import { ApiError } from '../../../api/apiError';
import { AuthContext } from '../../../contexts/authContext';
import type { AuthValue } from '../../../contexts/authContext';

vi.mock('../../../api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/users')>()),
  checkNicknameAvailability: vi.fn(),
}));

const { checkNicknameAvailability } = await import('../../../api/users');

const completeSocialSignup = vi.fn();

const STATE = {
  provider: 'google',
  signupToken: 'signup-token',
  suggestedNickname: '구글사용자',
};

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

function renderPage(state: unknown = STATE) {
  const auth = {
    status: 'anonymous',
    user: null,
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeSocialSignup,
    signOut: vi.fn(),
  } as unknown as AuthValue;

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[{ pathname: '/signup/social/profile', state }]}>
        <SocialSignupProfilePage />
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

async function fill() {
  fireEvent.click(screen.getByRole('button', { name: '중복 확인' }));
  await screen.findByText('사용할 수 있는 닉네임이에요.');
  fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
  fireEvent.click(screen.getByRole('button', { name: '여성' }));
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '가입하기' }));
}

beforeEach(() => {
  completeSocialSignup.mockReset().mockResolvedValue({
    userId: 7,
    nickname: '구글사용자',
    onboardingCompleted: false,
  });
  vi.mocked(checkNicknameAvailability)
    .mockReset()
    .mockImplementation(async (nickname) => ({ nickname, available: true, reason: null }));
});

describe('0.3-A 소셜 가입 정보 입력', () => {
  it('제안 닉네임이 들어와 있다', () => {
    renderPage();

    expect(screen.getByLabelText('닉네임')).toHaveValue('구글사용자');
  });

  it('이메일·비밀번호는 묻지 않는다', () => {
    renderPage();

    expect(screen.queryByLabelText('이메일')).toBeNull();
    expect(screen.queryByLabelText('비밀번호')).toBeNull();
  });

  it('계약이 받는 필드를 그대로 보낸다', async () => {
    renderPage();

    await fill();
    submit();

    await waitFor(() => {
      expect(completeSocialSignup).toHaveBeenCalledWith({
        signupToken: 'signup-token',
        nickname: '구글사용자',
        birthDate: '1999-03-14',
        gender: 'FEMALE',
      });
    });
  });

  it('가입하면 0.5로 간다', async () => {
    renderPage();

    await fill();
    submit();

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/signup/complete'),
    );
  });

  it('중복 확인을 하지 않으면 보내지 않는다', async () => {
    renderPage();

    fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
    fireEvent.click(screen.getByRole('button', { name: '여성' }));
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('닉네임 중복 확인');
    expect(completeSocialSignup).not.toHaveBeenCalled();
  });

  it('가입 토큰이 만료되면 소셜 로그인부터 다시 하라고 알린다', async () => {
    completeSocialSignup.mockRejectedValue(
      new ApiError(401, 'SIGNUP_TOKEN_INVALID', '가입 토큰이 유효하지 않습니다'),
    );
    renderPage();

    await fill();
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('소셜 로그인부터 다시');
  });

  it('같은 이메일 계정이 있으면 연결 경로를 안내한다', async () => {
    completeSocialSignup.mockRejectedValue(
      new ApiError(409, 'EMAIL_DUPLICATED', '이미 가입된 이메일입니다'),
    );
    renderPage();

    await fill();
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('소셜 계정을 연결');
  });

  it('닉네임을 사이에 뺏기면 확인을 풀어 다시 고르게 한다', async () => {
    completeSocialSignup.mockRejectedValue(
      new ApiError(409, 'NICKNAME_DUPLICATED', '이미 사용 중인 닉네임입니다'),
    );
    renderPage();

    await fill();
    submit();

    // 확인이 풀려 다시 누를 수 있다. 통과한 상태에서는 잠겨 있던 버튼이다
    await waitFor(() => expect(screen.getByRole('button', { name: '중복 확인' })).toBeEnabled());
  });

  it('가입 토큰 없이 들어오면 0.2로 돌려보낸다', async () => {
    renderPage(null);

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/signup'));
    expect(screen.queryByRole('button', { name: '가입하기' })).toBeNull();
  });
});
