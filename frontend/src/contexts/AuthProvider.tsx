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
import { login, logout, restoreSession, signup, socialLogin, socialSignup } from '../api/auth';
import { onSessionEnd } from '../api/session';
import { forgetWebPush, releaseWebPush, syncWebPush } from '../pwa/webPush';
import { AuthContext } from './authContext';
import type { ReactNode } from 'react';
import type { AuthStatus, AuthValue } from './authContext';
import type {
  AuthenticatedUser,
  SignupRequest,
  SocialProvider,
  SocialSignupRequest,
} from '../types/auth';

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

  /*
   * 로그아웃 상태가 되면 이 기기의 푸시 알림도 끈다(업무 -583, 사용자 결정 2026-09-19). 누른
   * 로그아웃은 `signOut`이 서버 등록까지 지운다. 세션이 만료되거나 앱을 열었을 때 이미 끊겨
   * 있으면 토큰이 없어 서버 해제(API-077)를 보낼 수 없으므로 기기 등록만 지운다 — 그러면 서버의
   * 다음 발송이 Push Service에서 만료로 돌아온다.
   */
  useEffect(
    () =>
      onSessionEnd((reason) => {
        if (reason !== 'signed-out') void forgetWebPush();
        setState(ANONYMOUS);
      }),
    [],
  );

  /*
   * 로그인 상태가 되면 이 기기의 푸시 구독을 서버에 다시 올린다(업무 -583). 구독이 없으면
   * 아무것도 하지 않는다. 같은 기기에서 계정을 바꿨다면 서버가 새 계정으로 옮긴다.
   */
  const userId = state.user?.userId ?? null;

  useEffect(() => {
    if (userId !== null) void syncWebPush();
  }, [userId]);

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

  /*
   * 가입 응답이 세션을 함께 내리므로(API-001) 여기까지 올려야 화면이 로그인 상태가 된다.
   * `api/auth`가 Access Token을 보관해도 라우트 보호는 이 Context의 상태를 보기 때문에,
   * 올리지 않으면 가입 직후 온보딩으로 갈 때 로그인 화면으로 튕긴다.
   */
  const completeEmailSignup = useCallback(async (request: SignupRequest) => {
    const user = await signup(request);

    setState({ status: 'authenticated', user });

    return user;
  }, []);

  const completeSocialSignup = useCallback(async (request: SocialSignupRequest) => {
    const user = await socialSignup(request);

    setState({ status: 'authenticated', user });

    return user;
  }, []);

  const signOut = useCallback(async () => {
    // 로그아웃한 기기로 이 계정의 푸시가 오면 안 된다. 인증이 살아 있을 때 먼저 해제한다
    await releaseWebPush();
    await logout();

    setState(ANONYMOUS);
  }, []);

  const value = useMemo<AuthValue>(
    () => ({
      ...state,
      signIn,
      signInWithSocial,
      completeEmailSignup,
      completeSocialSignup,
      signOut,
    }),
    [state, signIn, signInWithSocial, completeEmailSignup, completeSocialSignup, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
