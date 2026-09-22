/*
 * 7.8-A 계정 관리의 소셜 계정 연결이 콜백에서 돌아올 때 들고 오는 값 (업무 S15P21E106-799).
 *
 * 소셜 인가는 다른 사이트로 나갔다가 `/oauth/callback`으로 돌아오는 전체 이동이다. 콜백이
 * 이 값을 라우터 state(`link`)에 실어 7.8-A로 보내면, 그 화면이 API-009를 한 번 부른다.
 * 7.8-A-1 회원 탈퇴의 본인 확인(`WithdrawPage/reauth.ts`, 업무 -754)과 같은 방식이다.
 */
import type { SocialProvider } from '../../../types/auth';

export type SocialLinkReturn =
  /** 제공자에서 새로 받은 인가 코드. 1회용이다 */
  | { provider: SocialProvider; code: string; redirectUri: string }
  /** 제공자 동의 화면에서 취소했거나 코드 없이 돌아왔다 */
  | { provider: SocialProvider; cancelled: true };

/** 라우터 state에서 꺼낸다. 다른 화면이 남긴 state나 모양이 다른 값은 없는 것으로 본다 */
export function readSocialLinkReturn(state: unknown): SocialLinkReturn | null {
  if (!state || typeof state !== 'object' || !('link' in state)) return null;

  const link = (state as { link: unknown }).link;

  if (!link || typeof link !== 'object') return null;

  const { provider, code, redirectUri } = link as Record<string, unknown>;

  if (typeof provider !== 'string') return null;
  if ('cancelled' in link) return { provider: provider as SocialProvider, cancelled: true };

  return typeof code === 'string' && typeof redirectUri === 'string'
    ? ({ provider, code, redirectUri } as SocialLinkReturn)
    : null;
}
