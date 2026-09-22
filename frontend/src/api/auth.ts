/*
 * 인증·계정 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md이다.
 *
 * Access Token은 응답에서 꺼내 `session`에 넘기고 화면에는 돌려주지 않는다. 토큰을
 * 화면으로 흘리면 어딘가에 보관될 여지가 생기는데, 보관 위치를 메모리 한 곳으로
 * 묶어 두는 것이 이 계층의 목적이다.
 */
import { request } from './client';
import {
  MOCK_ACCESS_TOKEN,
  endMockSession,
  getMockProfile,
  isMockCredential,
  isMockSession,
  startMockSession,
} from './mockAccount';
import { endSession, refreshAccessToken, startSession } from './session';
import { getMyProfile } from './users';
import type {
  AuthenticatedUser,
  EmailVerificationCodeResult,
  SignupRequest,
  SocialLinkResult,
  SocialLoginResult,
  SocialProvider,
  SocialSignupRequest,
} from '../types/auth';
import type { MyProfile } from '../types/user';

type LoginResponse = {
  accessToken: string;
  expiresIn: number;
  user: AuthenticatedUser;
};

/** API-007이 신규 사용자에게 주는 본문. 로그인 응답과 구조가 다르다 */
type SocialSignupRequiredResponse = {
  signupRequired: true;
  signupToken: string;
  suggestedNickname: string;
};

/**
 * API-078 이메일 인증번호 발송 · POST /api/v1/auth/email/verification-code
 *
 * 계정을 만들기 전에 이메일 소유를 확인하는 첫 단계다. 6자리 번호가 메일로 가고 응답은
 * 본문 없이 `202`다. **이미 가입된 이메일은 `409 EMAIL_DUPLICATED`로 돌아온다** — 이
 * 경로는 계정 존재를 숨기지 않는다(팀 결정, API-078 처리 규칙).
 */
export async function sendEmailVerificationCode(email: string): Promise<void> {
  await request<void>('/auth/email/verification-code', {
    method: 'POST',
    body: { email },
    auth: 'none',
  });
}

/**
 * API-079 이메일 인증번호 확인 · POST /api/v1/auth/email/verification-code/verify
 *
 * 번호가 맞으면 가입(API-001)에 쓰는 단기 토큰을 준다. 실패는 셋으로 갈린다.
 * 불일치(`VERIFICATION_CODE_MISMATCH`)는 다시 입력하면 되고, 만료(`410
 * VERIFICATION_CODE_EXPIRED`)와 시도 소진(`VERIFICATION_CODE_EXHAUSTED`)은 번호를
 * 다시 받아야 한다.
 */
export async function verifyEmailVerificationCode(
  email: string,
  code: string,
): Promise<EmailVerificationCodeResult> {
  return request<EmailVerificationCodeResult>('/auth/email/verification-code/verify', {
    method: 'POST',
    body: { email, code },
    auth: 'none',
  });
}

/**
 * API-001 이메일 회원가입 · POST /api/v1/auth/signup
 *
 * 가입과 동시에 로그인된다. 이메일 소유는 인증번호로 이미 확인했으므로 계정이 곧바로
 * `ACTIVE`가 되고 응답이 소셜 가입(API-008)과 같은 형태로 Access Token과 refresh 쿠키를
 * 함께 내린다(S15P21E106-603). 그래서 이 함수도 소셜 가입처럼 세션을 열고 사용자만
 * 돌려준다.
 */
export async function signup(body: SignupRequest): Promise<AuthenticatedUser> {
  const response = await request<LoginResponse>('/auth/signup', {
    method: 'POST',
    body,
    auth: 'none',
  });

  startSession(response.accessToken);

  return response.user;
}

/**
 * API-004 이메일 로그인 · POST /api/v1/auth/login
 *
 * Refresh Token은 응답 본문이 아니라 httpOnly 쿠키로 온다. 여기서 할 일은 Access
 * Token을 세션에 넣는 것뿐이다.
 */
export async function login(email: string, password: string): Promise<AuthenticatedUser> {
  // 개발 서버의 목업 계정은 서버로 보내지 않는다(`mockAccount.ts`)
  if (isMockCredential(email, password)) {
    startMockSession();
    startSession(MOCK_ACCESS_TOKEN);

    return toUser(getMockProfile());
  }

  const response = await request<LoginResponse>('/auth/login', {
    method: 'POST',
    body: { email, password },
    auth: 'none',
  });

  startSession(response.accessToken);

  return response.user;
}

/**
 * API-005 로그아웃 · POST /api/v1/auth/logout
 *
 * 서버 응답과 무관하게 로컬 세션을 끊는다. 로그아웃은 멱등이고, 실패를 알려도
 * 사용자가 할 수 있는 일이 없다.
 */
export async function logout(): Promise<void> {
  if (isMockSession()) {
    endMockSession();
    endSession('signed-out');

    return;
  }

  try {
    /*
     * 401이어도 재발급하지 않는다. 끝내려는 세션을 되살릴 이유가 없고, 재발급이 거절되면
     * 사용자가 누른 로그아웃에 "세션이 만료됐다"는 안내와 이동이 겹친다(리뷰 B-6).
     */
    await request<void>('/auth/logout', { method: 'POST', refreshOnUnauthorized: false });
  } finally {
    endSession('signed-out');
  }
}

/**
 * API-010 비밀번호 재설정 요청 · POST /api/v1/auth/password/reset-request
 *
 * 인증 메일 재발송(API-003)과 같은 이유로 계정이 있는지 드러내지 않고 본문 없이
 * `202`로 답한다. 성공 응답으로 가입 여부를 판단할 수 없다.
 *
 * 걸릴 수 있는 것은 요청 제한(`429 RATE_LIMITED`)뿐이다. 이메일 축과 IP 축 중 어디서
 * 걸렸는지도 응답이 구분하지 않으므로 화면도 구분하지 않는다.
 */
export async function requestPasswordReset(email: string): Promise<void> {
  await request<void>('/auth/password/reset-request', {
    method: 'POST',
    body: { email },
    auth: 'none',
  });
}

/**
 * API-011 비밀번호 재설정 · POST /api/v1/auth/password/reset
 *
 * `token`은 재설정 메일 링크에 실려 오는 1회용 불투명 문자열이다. 이메일 인증
 * (API-002)과 같이 클라이언트가 해석하지 않는다.
 *
 * 성공하면 서버가 그 사용자의 Refresh Token을 모두 폐기한다(처리 규칙). 이 호출은
 * 공개 API라 지금 세션과 무관하므로 여기서 세션을 건드리지 않는다.
 */
export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await request<void>('/auth/password/reset', {
    method: 'POST',
    body: { token, newPassword },
    auth: 'none',
  });
}

/**
 * API-012 비밀번호 변경 · PUT /api/v1/users/me/password
 *
 * 경로는 `users`지만 이 파일에 둔다. 성공하면 Refresh Token이 모두 폐기돼 세션이
 * 끊기므로(처리 규칙) 세션을 다루는 함수들과 같은 자리에 있는 편이 낫다.
 *
 * `[!]` 현재 비밀번호가 틀리면 `401 INVALID_CREDENTIALS`인데, 공통 클라이언트는 401을
 * 만나면 토큰을 재발급하고 원요청을 한 번 더 보낸다(`client.ts`). 재시도해도 같은
 * `401 INVALID_CREDENTIALS`가 오므로 화면이 받는 오류는 같고, 요청이 한 번 더 나가는
 * 것만 다르다. 계약이 이 코드를 두 뜻으로 쓰는 탓이라 클라이언트를 고치지 않았다.
 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await request<void>('/users/me/password', {
    method: 'PUT',
    body: { currentPassword, newPassword },
  });
}

/**
 * 새로고침 뒤 세션 복구.
 *
 * Access Token은 메모리에만 있어 새로고침하면 사라진다. 쿠키의 Refresh Token으로
 * 재발급해 보고, 되면 프로필(API-014)까지 읽어 로그인 상태를 되돌린다. 재발급이
 * 거절되면 비로그인 상태다.
 */
export async function restoreSession(): Promise<AuthenticatedUser | null> {
  if (isMockSession()) {
    startSession(MOCK_ACCESS_TOKEN);

    return toUser(getMockProfile());
  }

  const token = await refreshAccessToken();

  if (!token) {
    return null;
  }

  return toUser(await getMyProfile());
}

function toUser(profile: MyProfile): AuthenticatedUser {
  return {
    userId: profile.userId,
    nickname: profile.nickname,
    onboardingCompleted: profile.onboardingCompleted,
  };
}

/**
 * API-007 소셜 로그인 · POST /api/v1/auth/oauth/{provider}
 *
 * 인가 코드는 **백엔드가 제공자와 교환한다**(API-007 처리 규칙). 프론트는 콜백에서 받은
 * 코드와 그때 쓴 `redirectUri`를 그대로 넘긴다. `redirectUri`는 백엔드 허용 목록과
 * 정확히 일치해야 하고 다르면 제공자를 부르지 않고 `VALIDATION_FAILED`로 거절된다.
 *
 * 연결된 계정이 있으면 이메일 로그인과 같은 본문·쿠키가 오고, 없으면 `signupToken`이
 * 온다. 둘 다 200이라 본문으로 가른다.
 */
export async function socialLogin(
  provider: SocialProvider,
  code: string,
  redirectUri: string,
): Promise<SocialLoginResult> {
  const response = await request<LoginResponse | SocialSignupRequiredResponse>(
    `/auth/oauth/${provider}`,
    { method: 'POST', body: { code, redirectUri }, auth: 'none' },
  );

  if ('signupRequired' in response && response.signupRequired) {
    return {
      signupRequired: true,
      signupToken: response.signupToken,
      suggestedNickname: response.suggestedNickname,
    };
  }

  const login = response as LoginResponse;

  startSession(login.accessToken);

  return { signupRequired: false, user: login.user };
}

/**
 * API-008 소셜 회원가입 완료 · POST /api/v1/auth/oauth/signup
 *
 * 가입과 동시에 로그인되므로 응답은 로그인과 같다. 이메일 가입(API-001)과 달리 계정이
 * 곧바로 `ACTIVE`라 이메일 인증을 기다리지 않는다.
 */
export async function socialSignup(body: SocialSignupRequest): Promise<AuthenticatedUser> {
  const response = await request<LoginResponse>('/auth/oauth/signup', {
    method: 'POST',
    body,
    auth: 'none',
  });

  startSession(response.accessToken);

  return response.user;
}

/**
 * API-009 소셜 계정 연결 · POST /api/v1/users/me/oauth/{provider}
 *
 * 로그인한 계정에 소셜 로그인 수단을 더한다. 7.8-A 계정 관리의 `소셜 계정` 묶음이 부른다
 * (업무 -799). 와이어프레임 7.8-A에 진입 자리가 없어 업무 -499는 전송 계층까지만 두었다가
 * 그 업무에서 화면을 붙였다. 연결은 가입과 달리 다른 콜백 주소를 쓸 수 있으므로
 * `redirectUri`를 받는다 — 지금 화면은 로그인과 같은 `oauthRedirectUri()`를 쓴다.
 */
export async function linkSocialAccount(
  provider: SocialProvider,
  code: string,
  redirectUri: string,
): Promise<SocialLinkResult> {
  return request<SocialLinkResult>(`/users/me/oauth/${provider}`, {
    method: 'POST',
    body: { code, redirectUri },
  });
}
