/*
 * 사용자 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md이다.
 */
import { mockMyProfile } from '../mocks/user';
import { respond } from './mockTransport';
import type { MyProfile } from '../types/user';

/** API-014 내 프로필 조회 · GET /api/v1/users/me */
export async function getMyProfile(): Promise<MyProfile> {
  return respond(mockMyProfile);
}
