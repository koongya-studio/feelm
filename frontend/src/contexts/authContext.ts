/*
 * 인증 세션 Context 정의와 조회 훅.
 *
 * Provider는 AuthProvider.tsx에 둔다. 한 파일이 컴포넌트와 그 밖의 값을 함께 export하면
 * Fast Refresh가 동작하지 않으므로(react-refresh/only-export-components) 컴포넌트가
 * 아닌 것만 이 파일에 모았다.
 */
import { createContext, useContext } from 'react';
import type { AuthenticatedUser } from '../types/auth';

export type AuthStatus =
  /** 쿠키로 세션을 되살리는 중. 아직 로그인 여부를 모른다 */
  'restoring' | 'authenticated' | 'anonymous';

export type AuthValue = {
  status: AuthStatus;
  /** `status`가 `authenticated`일 때만 값이 있다 */
  user: AuthenticatedUser | null;
  /** 실패하면 `ApiError`를 던진다. 화면이 오류 코드로 문구를 가른다 */
  signIn: (email: string, password: string) => Promise<AuthenticatedUser>;
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
