/*
 * 사용자 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md이다.
 *
 * 목업을 걷어내고 실제 API를 부르는 첫 도메인이다(업무 S15P21E106-364). 응답에는
 * 이메일·생년월일 등 더 많은 필드가 있지만 `MyProfile`이 선언한 것만 쓴다. 나머지는
 * MY 화면 업무에서 타입에 추가한다.
 */
import { request } from './client';
import type { MyProfile } from '../types/user';

/**
 * API-014 내 프로필 조회 · GET /api/v1/users/me
 *
 * 회원 전용이다. 로그인하지 않았거나 세션이 끊겼으면 `401`이며, 호출한 화면이
 * 오류 상태로 처리한다.
 */
export async function getMyProfile(): Promise<MyProfile> {
  return request<MyProfile>('/users/me');
}
