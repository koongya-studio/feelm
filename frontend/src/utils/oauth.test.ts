/*
 * 소셜 로그인 시작과 콜백 확인 (업무 S15P21E106-499)
 *
 * 세 곳이 같아야 하는 `redirectUri`와 위조를 거르는 `state`를 고정한다. 둘 다 틀리면
 * 제공자나 백엔드가 거절하는데, 화면에서는 "로그인이 안 된다"로만 보여 원인을 찾기
 * 어려운 자리다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  authorizeUrl,
  clearOAuthRequest,
  isOAuthConfigured,
  oauthRedirectUri,
  readOAuthRequest,
  toSocialProvider,
} from './oauth';

beforeEach(() => {
  window.sessionStorage.clear();
  vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'google-client');
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('소셜 로그인 시작', () => {
  it('자격 증명이 없는 제공자는 열지 않는다', () => {
    expect(isOAuthConfigured('google')).toBe(true);
    expect(isOAuthConfigured('kakao')).toBe(false);
  });

  it('콜백 주소는 앱이 놓인 자리에서 만든다', () => {
    expect(oauthRedirectUri()).toBe(`${window.location.origin}/oauth/callback`);
  });

  it('등록한 주소가 다르면 빌드 변수로 덮어쓴다', () => {
    vi.stubEnv('VITE_OAUTH_REDIRECT_URI', 'https://feelm.app/app/oauth/callback');

    expect(oauthRedirectUri()).toBe('https://feelm.app/app/oauth/callback');
  });

  it('동의 화면 주소에 계약이 요구하는 값을 싣는다', () => {
    const authorize = new URL(authorizeUrl('google', 'login'));

    expect(authorize.origin + authorize.pathname).toBe(
      'https://accounts.google.com/o/oauth2/v2/auth',
    );
    expect(authorize.searchParams.get('client_id')).toBe('google-client');
    expect(authorize.searchParams.get('response_type')).toBe('code');
    expect(authorize.searchParams.get('redirect_uri')).toBe(oauthRedirectUri());
    // 이메일을 받지 못하면 백엔드가 `OAUTH_EMAIL_REQUIRED`로 거절한다
    expect(authorize.searchParams.get('scope')).toBe('openid email profile');
  });
});

describe('콜백 확인', () => {
  it('보낸 state로 돌아오면 그 요청을 돌려준다', () => {
    const state = new URL(authorizeUrl('google', 'signup')).searchParams.get('state');
    const request = readOAuthRequest(state);

    expect(request).toMatchObject({ provider: 'google', intent: 'signup' });
    expect(request?.redirectUri).toBe(oauthRedirectUri());
  });

  it('state가 다르면 이 앱이 시작한 요청이 아니다', () => {
    authorizeUrl('google', 'login');

    expect(readOAuthRequest('남이-보낸-값')).toBeNull();
  });

  it('읽어도 남아 있고 지우면 사라진다', () => {
    const state = new URL(authorizeUrl('google', 'login')).searchParams.get('state');

    expect(readOAuthRequest(state)).not.toBeNull();
    expect(readOAuthRequest(state)).not.toBeNull();

    clearOAuthRequest();

    expect(readOAuthRequest(state)).toBeNull();
  });
});

describe('회원 탈퇴 본인 확인(업무 -754)', () => {
  it('탈퇴 목적도 같은 콜백 주소로 요청을 남긴다. 콜백이 목적을 보고 갈라 보낸다', () => {
    const url = new URL(authorizeUrl('google', 'withdraw'));
    const request = readOAuthRequest(url.searchParams.get('state'));

    expect(request?.intent).toBe('withdraw');
    // 계정 연결(API-009)과 같은 허용 목록 값을 쓴다(업무 -435 결정 D5)
    expect(url.searchParams.get('redirect_uri')).toBe(oauthRedirectUri());
  });

  it('API-014 linkedProviders(대문자)를 앱의 제공자로 바꾸고 모르는 이름은 버린다', () => {
    expect(toSocialProvider('GOOGLE')).toBe('google');
    expect(toSocialProvider('kakao')).toBe('kakao');
    expect(toSocialProvider('APPLE')).toBeNull();
  });
});
