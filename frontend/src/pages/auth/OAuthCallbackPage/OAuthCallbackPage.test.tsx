/*
 * 소셜 로그인 콜백 — 갈림길과 실패 안내 (업무 S15P21E106-499)
 *
 * 이 화면은 보이는 것이 거의 없고 어디로 보내느냐가 전부다. API-007이 로그인과 가입을
 * 같은 200으로 주기 때문에 본문을 잘못 읽으면 세션 없이 홈으로 들어가거나, 가입이
 * 남았는데 로그인한 것처럼 보인다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import OAuthCallbackPage from './index';
import { ApiError } from '../../../api/apiError';
import { AuthContext } from '../../../contexts/authContext';
import type { AuthValue } from '../../../contexts/authContext';
import type { SocialLoginResult } from '../../../types/auth';

const signInWithSocial = vi.fn();

const STATE = 'state-1234';
const REQUEST = {
  provider: 'google',
  intent: 'login',
  state: STATE,
  redirectUri: 'http://localhost:3000/oauth/callback',
};

/** 이동 결과와 함께 넘긴 값까지 본다. 0.3-A는 `signupToken` 없이는 아무것도 못 한다 */
function LocationProbe() {
  const location = useLocation();

  return (
    <div data-testid="location">
      {location.pathname}
      {location.state ? JSON.stringify(location.state) : ''}
    </div>
  );
}

function renderCallback(search: string) {
  const auth = {
    status: 'anonymous',
    user: null,
    signIn: vi.fn(),
    signInWithSocial,
    completeSocialSignup: vi.fn(),
    signOut: vi.fn(),
  } as unknown as AuthValue;

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[`/oauth/callback${search}`]}>
        <OAuthCallbackPage />
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

function storeRequest() {
  window.sessionStorage.setItem('feelm.oauth', JSON.stringify(REQUEST));
}

beforeEach(() => {
  window.sessionStorage.clear();
  signInWithSocial.mockReset();
});

describe('소셜 로그인 콜백', () => {
  it('연결된 계정이 있으면 취향 설정 여부로 갈라 보낸다', async () => {
    storeRequest();
    signInWithSocial.mockResolvedValue({
      signupRequired: false,
      user: { userId: 7, nickname: '팝콘러버', onboardingCompleted: true },
    } satisfies SocialLoginResult);

    renderCallback(`?code=auth-code&state=${STATE}`);

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/'));
    expect(signInWithSocial).toHaveBeenCalledWith('google', 'auth-code', REQUEST.redirectUri);
  });

  it('취향 설정 전이면 초기 설정으로 보낸다', async () => {
    storeRequest();
    signInWithSocial.mockResolvedValue({
      signupRequired: false,
      user: { userId: 7, nickname: '팝콘러버', onboardingCompleted: false },
    } satisfies SocialLoginResult);

    renderCallback(`?code=auth-code&state=${STATE}`);

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent('/onboarding/taste'),
    );
  });

  it('가입이 남았으면 가입 토큰과 함께 0.3-A로 보낸다', async () => {
    storeRequest();
    signInWithSocial.mockResolvedValue({
      signupRequired: true,
      signupToken: 'signup-token',
      suggestedNickname: '구글사용자',
    } satisfies SocialLoginResult);

    renderCallback(`?code=auth-code&state=${STATE}`);

    const location = await screen.findByTestId('location');

    await waitFor(() => expect(location).toHaveTextContent('/signup/social/profile'));
    expect(location).toHaveTextContent('signup-token');
    expect(location).toHaveTextContent('구글사용자');
  });

  it('보낸 적 없는 state로 돌아오면 코드를 넘기지 않는다', async () => {
    storeRequest();

    renderCallback('?code=auth-code&state=남이-보낸-값');

    expect(await screen.findByRole('alert')).toHaveTextContent('요청이 올바르지 않아요');
    expect(signInWithSocial).not.toHaveBeenCalled();
  });

  it('동의를 취소하면 그렇게 알린다', async () => {
    storeRequest();

    renderCallback(`?error=access_denied&state=${STATE}`);

    expect(await screen.findByRole('alert')).toHaveTextContent('취소했어요');
    expect(signInWithSocial).not.toHaveBeenCalled();
  });

  it('제공자가 이메일을 주지 않으면 무엇을 해야 하는지 알린다', async () => {
    storeRequest();
    signInWithSocial.mockRejectedValue(
      new ApiError(400, 'OAUTH_EMAIL_REQUIRED', '이메일이 필요합니다'),
    );

    renderCallback(`?code=auth-code&state=${STATE}`);

    expect(await screen.findByRole('alert')).toHaveTextContent('이메일 제공에 동의');
  });
});

/*
 * 회원 탈퇴의 본인 확인(업무 -754). 같은 콜백 주소로 돌아오지만 로그인하지 않고 7.8-A-1로
 * 넘긴다 — 인가 코드는 1회용이라 여기서 로그인에 쓰면 탈퇴에 쓸 수 없다.
 */
describe('회원 탈퇴 본인 확인 콜백', () => {
  function storeWithdrawRequest() {
    window.sessionStorage.setItem(
      'feelm.oauth',
      JSON.stringify({ ...REQUEST, intent: 'withdraw' }),
    );
  }

  it('로그인하지 않고 코드를 들고 7.8-A-1로 돌아간다', async () => {
    storeWithdrawRequest();

    renderCallback(`?code=auth-code&state=${STATE}`);

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/my/settings/account/withdraw{"reauth":{"provider":"google","code":"auth-code","redirectUri":"http://localhost:3000/oauth/callback"}}',
      ),
    );
    expect(signInWithSocial).not.toHaveBeenCalled();
    // 같은 요청으로 다시 들어오지 못하게 지운다
    expect(window.sessionStorage.getItem('feelm.oauth')).toBeNull();
  });

  it('동의를 취소해도 로그인 화면이 아니라 7.8-A-1로 돌아가 거기서 알린다', async () => {
    storeWithdrawRequest();

    renderCallback(`?error=access_denied&state=${STATE}`);

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/my/settings/account/withdraw{"reauth":{"cancelled":true}}',
      ),
    );
    expect(screen.queryByRole('button', { name: '로그인 화면으로' })).toBeNull();
    expect(signInWithSocial).not.toHaveBeenCalled();
  });
});
