/*
 * 인증 세션 Provider.
 *
 * Access Token이 메모리에만 있으므로(`api/session`) 앱이 뜰 때마다 쿠키로 세션을 한 번
 * 되살린다. 그동안은 `restoring`이며, 로그인 여부를 모르는 이 구간을 화면이 구분할 수
 * 있어야 새로고침할 때마다 로그인 화면이 잠깐 스치는 일이 없다.
 *
 * 세션은 로그아웃 말고도 끊긴다. 재발급이 거절되면 아무 API 호출 도중에나 끊기므로
 * `onSessionEnd`를 구독해 상태를 되돌린다.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { login, logout, restoreSession, socialLogin, socialSignup } from '../api/auth';
import { onSessionEnd } from '../api/session';
import { AuthContext } from './authContext';
import type { ReactNode } from 'react';
import type { AuthStatus, AuthValue } from './authContext';
import type { AuthenticatedUser, SocialProvider, SocialSignupRequest } from '../types/auth';

type State = { status: AuthStatus; user: AuthenticatedUser | null };

const ANONYMOUS: State = { status: 'anonymous', user: null };

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>({ status: 'restoring', user: null });

  useEffect(() => {
    let active = true;

    const settle = (user: AuthenticatedUser | null) => {
      if (active) {
        setState(user ? { status: 'authenticated', user } : ANONYMOUS);
      }
    };

    // 복구 실패는 비로그인과 같게 다룬다. 앱 진입을 막을 이유가 없다
    void restoreSession()
      .then(settle)
      .catch(() => settle(null));

    return () => {
      active = false;
    };
  }, []);

  useEffect(() => onSessionEnd(() => setState(ANONYMOUS)), []);

  const signIn = useCallback(async (email: string, password: string) => {
    const user = await login(email, password);

    setState({ status: 'authenticated', user });

    return user;
  }, []);

  const signInWithSocial = useCallback(
    async (provider: SocialProvider, code: string, redirectUri: string) => {
      const result = await socialLogin(provider, code, redirectUri);

      // 가입이 남은 경우에는 아직 세션이 없다. 로그인 상태로 올리면 안 된다
      if (!result.signupRequired) {
        setState({ status: 'authenticated', user: result.user });
      }

      return result;
    },
    [],
  );

  const completeSocialSignup = useCallback(async (request: SocialSignupRequest) => {
    const user = await socialSignup(request);

    setState({ status: 'authenticated', user });

    return user;
  }, []);

  const signOut = useCallback(async () => {
    await logout();

    setState(ANONYMOUS);
  }, []);

  const value = useMemo<AuthValue>(
    () => ({ ...state, signIn, signInWithSocial, completeSocialSignup, signOut }),
    [state, signIn, signInWithSocial, completeSocialSignup, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
