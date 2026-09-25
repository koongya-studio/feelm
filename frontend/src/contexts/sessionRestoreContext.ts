/*
 * 새로고침 세션 복구의 일시 실패 (업무 S15P21E106-908)
 *
 * 재발급은 됐는데 내 프로필(API-014)을 끝내 못 받은 상태다. 세션은 살아 있으므로 비로그인으로
 * 떨어뜨리지 않는다 — 한때 그렇게 해서 서버가 잠깐 흔들리면 로그인 화면으로 튕겼다. 상태는
 * `restoring` 그대로 두고, 로그인이 필요한 자리(`RequireAuth` · `HomeEntry`)가 이 값을 보고
 * 빈 화면 대신 다시 시도를 보여 준다.
 *
 * `AuthValue`에 넣지 않은 까닭은 그 타입을 직접 만드는 테스트 · 화면이 많아서다. 이 값을 읽는
 * 곳은 두 자리뿐이다. Provider는 `AuthProvider`다.
 */
import { createContext, useContext } from 'react';

export type SessionRestore = {
  /** 세션은 살아 있는데 복구를 끝내지 못했다. 다시 시도를 기다린다 */
  failed: boolean;
  /** 복구를 처음부터 다시 한다 */
  retry: () => void;
};

export const SessionRestoreContext = createContext<SessionRestore>({
  failed: false,
  retry: () => undefined,
});

export function useSessionRestore(): SessionRestore {
  return useContext(SessionRestoreContext);
}
