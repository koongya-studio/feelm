/*
 * 소셜 준비 중 안내 (업무 S15P21E106-765)
 *
 * 0.6 로그인 · 0.2 가입 방식 선택이 **같은 기준**으로 안내하는지 본다.
 * 한때 0.6에만 안내가 있어 가입 화면에서는 잠긴 버튼만 남았다. 기준은 빌드의 client-id라
 * 시험마다 빌드 변수를 바꾼다.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SocialNotice from '.';
import LoginPage from '../../pages/auth/LoginPage';
import SignupMethodPage from '../../pages/auth/SignupMethodPage';
import { AuthContext } from '../../contexts/authContext';
import type { AuthValue } from '../../contexts/authContext';

afterEach(() => {
  vi.unstubAllEnvs();
});

/** 모든 제공자의 client-id를 비운다. 로컬 `.env` 값과 무관하게 같은 상태에서 시작한다 */
function noSocial() {
  vi.stubEnv('VITE_GOOGLE_CLIENT_ID', '');
  vi.stubEnv('VITE_KAKAO_CLIENT_ID', '');
  vi.stubEnv('VITE_NAVER_CLIENT_ID', '');
}

function renderPage(page: React.ReactNode) {
  const auth = {
    status: 'anonymous',
    user: null,
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeEmailSignup: vi.fn(),
    completeSocialSignup: vi.fn(),
    signOut: vi.fn(),
  } as unknown as AuthValue;

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter>{page}</MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('소셜 준비 중 안내', () => {
  it('제공자가 하나도 열려 있지 않으면 알린다', () => {
    noSocial();
    render(<SocialNotice action="가입" />);

    expect(screen.getByText('소셜 가입은 준비 중입니다.')).toBeInTheDocument();
  });

  it('하나라도 열려 있으면 내린다', () => {
    noSocial();
    vi.stubEnv('VITE_KAKAO_CLIENT_ID', 'kakao-client');
    render(<SocialNotice action="로그인" />);

    expect(screen.queryByText(/준비 중입니다/)).toBeNull();
  });

  it('네이버만 설정돼 있어도 노출 가능한 소셜 인증이 없다고 알린다', () => {
    noSocial();
    vi.stubEnv('VITE_NAVER_CLIENT_ID', 'naver-client');
    render(<SocialNotice action="로그인" />);

    expect(screen.getByText('소셜 로그인은 준비 중입니다.')).toBeInTheDocument();
  });

  it.each([
    ['0.6 로그인', <LoginPage key="login" />],
    ['0.2 가입 방식 선택', <SignupMethodPage key="method" />],
  ])('%s에 네이버 인증 진입점을 노출하지 않는다', (_, page) => {
    noSocial();
    vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'google-client');
    vi.stubEnv('VITE_KAKAO_CLIENT_ID', 'kakao-client');
    vi.stubEnv('VITE_NAVER_CLIENT_ID', 'naver-client');
    renderPage(page);

    expect(screen.queryByRole('button', { name: /네이버/ })).toBeNull();
    expect(screen.getAllByRole('button', { name: /구글|카카오/ })).toHaveLength(2);
  });

  it.each([
    ['0.6 로그인', <LoginPage key="login" />, '소셜 로그인은 준비 중입니다.'],
    ['0.2 가입 방식 선택', <SignupMethodPage key="method" />, '소셜 가입은 준비 중입니다.'],
  ])('%s도 버튼이 모두 잠기면 같은 기준으로 알린다', (_, page, text) => {
    noSocial();
    renderPage(page);

    expect(screen.getByText(text)).toBeInTheDocument();
  });
});
