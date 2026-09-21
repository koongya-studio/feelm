/*
 * 소셜 로그인 시작과 콜백 확인.
 *
 * 인가 코드를 제공자와 교환하는 것은 백엔드다(API-007 처리 규칙). 프론트가 하는 일은
 * 제공자의 동의 화면으로 보내고, 돌아온 주소에서 `code`를 꺼내 백엔드에 넘기는 것뿐이다.
 *
 * **client-id는 번들에 들어간다.** OAuth 공개 클라이언트의 client-id는 공개값이라
 * 그래도 되지만 client-secret은 절대 여기 두지 않는다(백엔드만 안다,
 * `docs/secret-environment-variables.md` 4장).
 *
 * `redirectUri`는 세 곳이 **정확히 같은 문자열**이어야 한다 — 각 사 콘솔에 등록한 값,
 * 백엔드 허용 목록(`OAUTH_ALLOWED_REDIRECT_URIS`), 그리고 여기서 만드는 값. 하나라도
 * 다르면 제공자나 백엔드가 거절한다. 그래서 기본값은 앱 주소에서 계산하고, 등록한 값이
 * 다르면 `VITE_OAUTH_REDIRECT_URI`로 덮어쓴다.
 */
import { path } from '../routes/paths';
import { appUrl } from './appUrl';
import type { SocialProvider } from '../types/auth';

/** 제공자의 동의 화면 */
const AUTHORIZE_URL: Record<SocialProvider, string> = {
  google: 'https://accounts.google.com/o/oauth2/v2/auth',
  kakao: 'https://kauth.kakao.com/oauth/authorize',
  naver: 'https://nid.naver.com/oauth2.0/authorize',
};

/**
 * 추가로 요청할 범위.
 *
 * 구글은 이메일을 주지 않으면 백엔드가 `OAUTH_EMAIL_REQUIRED`로 거절하므로 범위를
 * 명시한다. 카카오·네이버는 콘솔의 동의 항목 설정을 따르며 범위를 붙이지 않는다.
 */
const SCOPE: Partial<Record<SocialProvider, string>> = {
  google: 'openid email profile',
};

/** 빌드 변수를 부를 때마다 읽는다. 모듈을 불러오는 시점에 굳히면 테스트가 바꿀 수 없다 */
function clientId(provider: SocialProvider): string {
  const env = import.meta.env;

  switch (provider) {
    case 'google':
      return env.VITE_GOOGLE_CLIENT_ID ?? '';
    case 'kakao':
      return env.VITE_KAKAO_CLIENT_ID ?? '';
    case 'naver':
      return env.VITE_NAVER_CLIENT_ID ?? '';
  }
}

/** 진행 중인 요청을 두는 자리. 탭을 닫으면 사라지고 다른 탭과 섞이지 않는다 */
const STORAGE_KEY = 'feelm.oauth';

/** 화면에 쓰는 제공자 이름 */
export const SOCIAL_PROVIDER_LABEL: Record<SocialProvider, string> = {
  google: '구글',
  kakao: '카카오',
  naver: '네이버',
};

/**
 * 콜백에서 이어 갈 곳. 로그인으로 시작했는지 가입으로 시작했는지에 따라 다르다.
 *
 * `withdraw`는 7.8-A-1 회원 탈퇴의 본인 확인이다(업무 -754). 콜백은 이 코드로 로그인하지
 * 않고 탈퇴 화면에 넘긴다 — 코드는 1회용이라 쓰는 곳이 한 곳이어야 한다.
 */
export type OAuthIntent = 'login' | 'signup' | 'withdraw';

/** 이미 연결된 제공자 이름(API-014 `linkedProviders`, 대문자로 온다)을 앱의 제공자로 바꾼다 */
export function toSocialProvider(name: string): SocialProvider | null {
  const lower = name.toLowerCase();

  return Object.hasOwn(AUTHORIZE_URL, lower) ? (lower as SocialProvider) : null;
}

export type OAuthRequest = {
  provider: SocialProvider;
  intent: OAuthIntent;
  /** 위조 요청을 거르는 값. 돌아온 `state`와 같아야 한다 */
  state: string;
  redirectUri: string;
  /**
   * 로그인이 끝나면 돌아갈 앱 안의 경로 (업무 -610).
   *
   * 보호 라우트가 0.6으로 보낼 때 남긴 주소다. 제공자를 다녀오면 라우터 state가 남지
   * 않으므로 요청과 함께 저장했다가 콜백이 읽는다. 없으면 3.1 필름이다.
   */
  returnTo?: string;
};

/** 자격 증명이 설정되지 않은 제공자는 백엔드도 `UNSUPPORTED_PROVIDER`로 답한다 */
export function isOAuthConfigured(provider: SocialProvider): boolean {
  return clientId(provider) !== '';
}

/**
 * 콜백 주소.
 *
 * 앱이 놓인 자리(`/app/`)는 `appUrl`이 붙인다(업무 -717). 개발과 배포의 호스트가 달라도
 * 같은 규칙으로 만들어지므로 환경마다 다른 값을 코드에 두지 않는다. 앱 밖으로 나가는
 * 주소를 만드는 자리는 모두 이 함수를 쓴다.
 */
export function oauthRedirectUri(): string {
  const configured = import.meta.env.VITE_OAUTH_REDIRECT_URI;

  if (configured) {
    return configured;
  }

  return appUrl(path.oauthCallback());
}

/** 난수 `state`. 브라우저가 주는 것을 쓰고, 없으면 덜 좋은 값이라도 만든다 */
function createState(): string {
  const random = window.crypto?.getRandomValues?.(new Uint8Array(16));

  if (random) {
    return [...random].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  }

  return `${Date.now().toString(16)}${Math.random().toString(16).slice(2)}`;
}

/**
 * 요청을 기억하고 동의 화면 주소를 만든다.
 *
 * 이동과 나누어 둔 것은 주소에 실리는 값이 계약과 각 사 콘솔에 맞아야 하는 부분이라
 * 따로 확인할 수 있어야 하기 때문이다. 실제 이동은 `startOAuth`가 한다.
 */
export function authorizeUrl(
  provider: SocialProvider,
  intent: OAuthIntent,
  returnTo?: string,
): string {
  const redirectUri = oauthRedirectUri();
  const state = createState();

  saveRequest({ provider, intent, state, redirectUri, ...(returnTo ? { returnTo } : {}) });

  const authorize = new URL(AUTHORIZE_URL[provider]);

  authorize.searchParams.set('client_id', clientId(provider));
  authorize.searchParams.set('redirect_uri', redirectUri);
  authorize.searchParams.set('response_type', 'code');
  authorize.searchParams.set('state', state);

  const scope = SCOPE[provider];

  if (scope) {
    authorize.searchParams.set('scope', scope);
  }

  return authorize.toString();
}

/**
 * 제공자의 동의 화면으로 보낸다.
 *
 * 앱 안의 이동이 아니라 다른 사이트로 나가는 것이라 `navigate`가 아니라
 * `location.assign`이다. 사용자가 동의를 취소하고 뒤로 오면 원래 화면이어야 하므로
 * 이 이동은 이력에 남긴다.
 */
export function startOAuth(provider: SocialProvider, intent: OAuthIntent, returnTo?: string): void {
  window.location.assign(authorizeUrl(provider, intent, returnTo));
}

function saveRequest(request: OAuthRequest): void {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(request));
  } catch {
    /*
     * 저장소를 막아 둔 브라우저에서도 로그인 자체는 되어야 한다. 이때 `state`를 맞춰 볼
     * 수 없으므로 콜백이 요청을 찾지 못하고 안내로 끝난다.
     */
  }
}

/**
 * 돌아온 `state`에 해당하는 요청.
 *
 * 값이 다르거나 없으면 이 앱이 시작한 요청이 아니다. 남의 인가 코드를 대신 넣어 로그인
 * 시키는 것(CSRF)을 막는 자리라 맞지 않으면 진행하지 않는다.
 *
 * **읽기만 한다.** 화면이 그리는 중에 불러도 되도록 지우는 것은 `clearOAuthRequest`로
 * 나누어 두었다. 콜백 화면은 주소만 보고 실패를 정해야 하는데, 읽으면서 지우면 같은
 * 렌더를 두 번 하는 개발 모드에서 두 번째가 요청을 찾지 못한다.
 */
export function readOAuthRequest(state: string | null): OAuthRequest | null {
  if (!state) {
    return null;
  }

  try {
    const saved = window.sessionStorage.getItem(STORAGE_KEY);

    if (!saved) {
      return null;
    }

    const request = JSON.parse(saved) as OAuthRequest;

    return request.state === state ? request : null;
  } catch {
    return null;
  }
}

/** 인가 코드를 쓴 뒤 요청을 지운다. 한 요청으로 두 번 로그인하지 않게 한다 */
export function clearOAuthRequest(): void {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // 저장소를 막아 둔 브라우저에서는 지울 것도 없다
  }
}
