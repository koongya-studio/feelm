/*
 * 인증·계정 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md이다.
 *
 * Access Token은 응답에서 꺼내 `session`에 넘기고 화면에는 돌려주지 않는다. 토큰을
 * 화면으로 흘리면 어딘가에 보관될 여지가 생기는데, 보관 위치를 메모리 한 곳으로
 * 묶어 두는 것이 이 계층의 목적이다.
 */
import { request } from './client';
import { endSession, refreshAccessToken, startSession } from './session';
import { getMyProfile } from './users';
import type {
  AuthenticatedUser,
  EmailVerificationResult,
  SignupRequest,
  SignupResult,
} from '../types/auth';

type LoginResponse = {
  accessToken: string;
  expiresIn: number;
  user: AuthenticatedUser;
};

/**
 * API-001 이메일 회원가입 · POST /api/v1/auth/signup
 *
 * 가입 직후 계정은 `PENDING_VERIFICATION`이다. 인증 메일의 링크로 API-002를 거쳐야
 * 로그인할 수 있으므로 이 호출만으로는 세션이 생기지 않는다.
 */
export async function signup(body: SignupRequest): Promise<SignupResult> {
  return request<SignupResult>('/auth/signup', { method: 'POST', body, auth: 'none' });
}

/**
 * API-002 이메일 인증 확인 · POST /api/v1/auth/email/verify
 *
 * `token`은 서버가 발급한 불투명 문자열이며 인증 메일 링크로 전달된다. 클라이언트가
 * 해석하지 않는다.
 */
export async function verifyEmail(token: string): Promise<EmailVerificationResult> {
  return request<EmailVerificationResult>('/auth/email/verify', {
    method: 'POST',
    body: { token },
    auth: 'none',
  });
}

/**
 * API-003 인증 메일 재발송 · POST /api/v1/auth/email/resend
 *
 * 계정이 있는지 드러내지 않으려고 본문 없이 `202`로 답한다. 그래서 성공 응답으로
 * 가입 여부를 판단할 수 없다.
 */
export async function resendVerificationMail(email: string): Promise<void> {
  await request<void>('/auth/email/resend', { method: 'POST', body: { email }, auth: 'none' });
}

/**
 * API-004 이메일 로그인 · POST /api/v1/auth/login
 *
 * Refresh Token은 응답 본문이 아니라 httpOnly 쿠키로 온다. 여기서 할 일은 Access
 * Token을 세션에 넣는 것뿐이다.
 */
export async function login(email: string, password: string): Promise<AuthenticatedUser> {
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
  try {
    await request<void>('/auth/logout', { method: 'POST' });
  } finally {
    endSession();
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
  const token = await refreshAccessToken();

  if (!token) {
    return null;
  }

  const profile = await getMyProfile();

  return {
    userId: profile.userId,
    nickname: profile.nickname,
    onboardingCompleted: profile.onboardingCompleted,
  };
}
