/*
 * 인증 세션 Context 정의와 조회 훅.
 *
 * Provider는 AuthProvider.tsx에 둔다. 한 파일이 컴포넌트와 그 밖의 값을 함께 export하면
 * Fast Refresh가 동작하지 않으므로(react-refresh/only-export-components) 컴포넌트가
 * 아닌 것만 이 파일에 모았다.
 */
import { createContext, useContext } from 'react';
import type {
  AuthenticatedUser,
  SignupRequest,
  SocialLoginResult,
  SocialProvider,
  SocialSignupRequest,
} from '../types/auth';

export type AuthStatus =
  /** 쿠키로 세션을 되살리는 중. 아직 로그인 여부를 모른다 */
  'restoring' | 'authenticated' | 'anonymous';

export type AuthValue = {
  status: AuthStatus;
  /** `status`가 `authenticated`일 때만 값이 있다 */
  user: AuthenticatedUser | null;
  /** 실패하면 `ApiError`를 던진다. 화면이 오류 코드로 문구를 가른다 */
  signIn: (email: string, password: string) => Promise<AuthenticatedUser>;
  /**
   * API-007. 연결된 계정이 없으면 로그인하지 않고 `signupRequired`를 그대로 돌려준다.
   * 그 경우 세션은 생기지 않고 0.3-A가 `signupToken`으로 가입을 마친다.
   */
  signInWithSocial: (
    provider: SocialProvider,
    code: string,
    redirectUri: string,
  ) => Promise<SocialLoginResult>;
  /**
   * API-001. 인증번호로 이메일 소유를 먼저 확인했으므로 가입과 동시에 로그인된다
   * (S15P21E106-603). 소셜 가입과 같은 자리다
   */
  completeEmailSignup: (request: SignupRequest) => Promise<AuthenticatedUser>;
  /** API-008. 가입과 동시에 로그인된다 */
  completeSocialSignup: (request: SocialSignupRequest) => Promise<AuthenticatedUser>;
  signOut: () => Promise<void>;
};

export const AuthContext = createContext<AuthValue | null>(null);

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);

  if (!value) {
    throw new Error('useAuth는 AuthProvider 안에서만 사용할 수 있습니다.');
  }

  return value;
}
