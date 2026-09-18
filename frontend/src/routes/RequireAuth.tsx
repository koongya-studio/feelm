/*
 * 로그인이 필요한 라우트의 문지기 (업무 S15P21E106-610).
 *
 * 와이어프레임 이동표 `비로그인 ⇢ 0.6 홈·필름·MY 접근 시 로그인으로 이동`을 라우터
 * 한 곳에서 처리한다(frontend-structure.md 3.7). 화면마다 로그인 여부를 보지 않는다 —
 * 회원 API가 401을 내면 `AuthProvider`가 비로그인으로 되돌리지만, 그때는 이미 보호
 * 화면이 한 번 그려진 뒤다.
 *
 * 세 상태를 가른다.
 * - `restoring`: 쿠키로 세션을 되살리는 중이다. 로그인 여부를 모르므로 **아무것도 그리지
 *   않는다.** 그동안은 스플래시가 덮고 있다(`SplashScreen`). 여기서 로그인 화면으로 보내면
 *   새로고침할 때마다 로그인 화면이 스친다
 * - `anonymous`: 0.6 로그인으로 보낸다. 가려던 주소를 `state.from`에 실어 로그인이 끝나면
 *   그 자리로 돌아온다(`LoginPage` · `OAuthCallbackPage`). `replace`라 뒤로 가기가 보호
 *   화면으로 되돌아오지 않는다
 * - `authenticated`: 자식 라우트를 그린다
 *
 * 공개 라우트(0.x 인증 화면 · 앱 진입점 `/` · 없는 경로)는 이 요소 아래에 두지 않는다
 * (`router.tsx`).
 */
import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from '../contexts/authContext';
import { path } from './paths';
import type { RequireAuthState } from './returnPath';

export default function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'restoring') {
    return null;
  }

  if (status === 'anonymous') {
    const from = `${location.pathname}${location.search}${location.hash}`;

    return <Navigate to={path.login()} replace state={{ from } satisfies RequireAuthState} />;
  }

  return <Outlet />;
}
