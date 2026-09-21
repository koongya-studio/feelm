/*
 * 7.8-A-1 회원 탈퇴의 소셜 본인 확인이 콜백에서 돌아올 때 들고 오는 값 (업무 -754).
 *
 * 소셜 인가는 다른 사이트로 나갔다가 `/oauth/callback`으로 돌아오는 전체 이동이다. 콜백이
 * 이 값을 라우터 state(`reauth`)에 실어 7.8-A-1로 보내면, 그 화면이 탈퇴 확인 상태를 되살리고
 * API-013을 한 번 부른다.
 */
import type { SocialProvider } from '../../../types/auth';

export type WithdrawReauth =
  /** 제공자에서 새로 받은 인가 코드. 1회용이다 */
  | { provider: SocialProvider; code: string; redirectUri: string }
  /** 제공자 동의 화면에서 취소했거나 코드 없이 돌아왔다 */
  | { cancelled: true };

/** 라우터 state에서 꺼낸다. 다른 화면이 남긴 state나 모양이 다른 값은 없는 것으로 본다 */
export function readWithdrawReauth(state: unknown): WithdrawReauth | null {
  if (!state || typeof state !== 'object' || !('reauth' in state)) return null;

  const reauth = (state as { reauth: unknown }).reauth;

  if (!reauth || typeof reauth !== 'object') return null;
  if ('cancelled' in reauth) return { cancelled: true };

  const { provider, code, redirectUri } = reauth as Record<string, unknown>;

  return typeof provider === 'string' && typeof code === 'string' && typeof redirectUri === 'string'
    ? ({ provider, code, redirectUri } as WithdrawReauth)
    : null;
}
