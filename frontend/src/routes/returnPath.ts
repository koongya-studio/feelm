/*
 * 로그인 뒤 돌아갈 주소 (업무 S15P21E106-610).
 *
 * `RequireAuth`가 0.6으로 보낼 때 `state.from`에 싣고, 0.6과 소셜 콜백이 읽는다. 컴포넌트
 * 파일에서 함수를 함께 export하면 Fast Refresh가 깨지므로 여기에 따로 둔다.
 */

/** `RequireAuth`가 `Navigate`에 싣는 state */
export type RequireAuthState = { from: string };

/**
 * 돌아갈 주소로 써도 되는지.
 *
 * 앱 안의 경로만 받는다. `//evil.example`처럼 다른 출처로 해석되는 값이나 절대 URL은
 * 열린 리다이렉트가 되므로 버린다.
 */
export function isReturnPath(value: unknown): value is string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//');
}
