/*
 * 인증 세션 Provider.
 *
 * Access Token이 메모리에만 있으므로(`api/session`) 앱이 뜰 때마다 쿠키로 세션을 한 번
 * 되살린다. 그동안은 `restoring`이며, 로그인 여부를 모르는 이 구간을 화면이 구분할 수
 * 있어야 새로고침할 때마다 로그인 화면이 잠깐 스치는 일이 없다.
 *
 * **복구가 일시 오류로 끝나면 비로그인으로 떨어뜨리지 않는다**(업무 -908). 재발급은 됐는데
 * 프로필을 서버 · 네트워크 사정으로 끝내 못 받았으면 세션은 살아 있다. 한때 모든 복구 실패를
 * 비로그인으로 다뤄, 서버가 잠깐 흔들리면 새로고침만으로 로그인 화면에 떨어졌다. 그때는
 * `restoring`을 유지하고 `SessionRestoreContext`로 다시 시도를 내려 준다. 재발급이 거절되면
 * (401) 세션이 끝나 `canRefresh()`가 내려가므로 지금처럼 비로그인이다.
 *
 * 세션은 로그아웃 말고도 끊긴다. 재발급이 거절되면 아무 API 호출 도중에나 끊기므로
 * `onSessionEnd`를 구독해 상태를 되돌린다.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { login, logout, restoreSession, signup, socialLogin, socialSignup } from '../api/auth';
import { startNotificationStream } from '../api/notificationStream';
import { isTransientFailure } from '../api/retry';
import { canRefresh, onSessionEnd } from '../api/session';
import { AuthContext } from './authContext';
import { SessionRestoreContext } from './sessionRestoreContext';
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
  /** 세션은 살아 있는데 복구를 끝내지 못했다 */
  const [restoreFailed, setRestoreFailed] = useState(false);
  /** 다시 시도할 때마다 늘려 복구를 처음부터 다시 돌린다 */
  const [restoreAttempt, setRestoreAttempt] = useState(0);

  useEffect(() => {
    let active = true;

    const settle = (user: AuthenticatedUser | null) => {
      if (active) {
        setState(user ? { status: 'authenticated', user } : ANONYMOUS);
      }
    };

    void restoreSession()
      .then(settle)
      .catch((error: unknown) => {
        if (!active) return;

        // 세션이 살아 있는 일시 실패만 붙든다. 그 밖의 복구 실패는 비로그인과 같다 — 앱
        // 진입을 막을 이유가 없다
        if (isTransientFailure(error) && canRefresh()) {
          setRestoreFailed(true);
        } else {
          settle(null);
        }
      });

    return () => {
      active = false;
    };
  }, [restoreAttempt]);

  const retryRestore = useCallback(() => {
    setRestoreFailed(false);
    setRestoreAttempt((count) => count + 1);
  }, []);

  const restore = useMemo(
    () => ({ failed: restoreFailed, retry: retryRestore }),
    [restoreFailed, retryRestore],
  );

  useEffect(
    () =>
      onSessionEnd(() => {
        setState(ANONYMOUS);
        setRestoreFailed(false);
      }),
    [],
  );

  useEffect(() => {
    if (state.status === 'authenticated') return startNotificationStream();
  }, [state.status, state.user?.userId]);

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

  return (
    <AuthContext.Provider value={value}>
      <SessionRestoreContext.Provider value={restore}>{children}</SessionRestoreContext.Provider>
    </AuthContext.Provider>
  );
}
