import { vi } from 'vitest';
import type { AuthValue } from '../contexts/authContext';

/** 화면 간 이동 테스트에서 사용하는 로그인 세션 */
export const testAuth: AuthValue = {
  status: 'authenticated',
  user: { userId: 7, nickname: '테스트 사용자', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};
