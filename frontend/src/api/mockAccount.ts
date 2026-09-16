/*
 * 개발 서버 전용 목업 계정.
 *
 * 로그인(API-004)과 내 프로필(API-014 · API-015)은 실제 백엔드를 부른다. 백엔드 계정이 없어도
 * 로그인해서 화면을 둘러볼 수 있도록, **`npm run dev`에서만** 아래 아이디 · 비밀번호를 받으면
 * 서버로 보내지 않고 목업 세션을 연다.
 *
 * - 배포 빌드에서는 `import.meta.env.DEV`가 `false`라 이 계정으로 로그인할 수 없다
 * - 목업 세션인지는 탭의 세션 저장소에 표시만 남긴다. 토큰이 아니다. 새로고침해도 로그인이
 *   유지되고, 탭을 닫거나 로그아웃하면 사라진다
 * - 프로필은 `mocks/user.ts`(닉네임 `김난다`)다. 7.2에서 바꾼 값은 이 탭에서만 남는다
 *
 * **실제 연동이 끝나면 목업과 함께 지운다.**
 */
import { mockMyProfile } from '../mocks/user';
import type { MyProfile } from '../types/user';

export const MOCK_ACCOUNT = { email: 'demo@feelm.kr', password: 'feelm1234!' };

const SESSION_KEY = 'feelm.mockAccount';
const PROFILE_KEY = 'feelm.mockAccount.profile';

export const MOCK_ACCESS_TOKEN = 'mock-access-token';

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/** 개발 서버에서 목업 계정의 아이디 · 비밀번호인지 */
export function isMockCredential(email: string, password: string): boolean {
  return (
    import.meta.env.DEV &&
    email.trim().toLowerCase() === MOCK_ACCOUNT.email &&
    password === MOCK_ACCOUNT.password
  );
}

export function isMockSession(): boolean {
  return import.meta.env.DEV && storage()?.getItem(SESSION_KEY) === 'true';
}

export function startMockSession(): void {
  storage()?.setItem(SESSION_KEY, 'true');
}

export function endMockSession(): void {
  storage()?.removeItem(SESSION_KEY);
  storage()?.removeItem(PROFILE_KEY);
}

export function getMockProfile(): MyProfile {
  const saved = storage()?.getItem(PROFILE_KEY);

  if (!saved) return mockMyProfile;

  try {
    return { ...mockMyProfile, ...(JSON.parse(saved) as Partial<MyProfile>) };
  } catch {
    return mockMyProfile;
  }
}

export function saveMockProfile(update: Partial<MyProfile>): MyProfile {
  const next = { ...getMockProfile(), ...update };
  storage()?.setItem(PROFILE_KEY, JSON.stringify(next));

  return next;
}
