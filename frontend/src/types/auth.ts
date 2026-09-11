/*
 * 인증·계정 타입. API-001~006과 docs/api/users.md의 응답 구조를 그대로 따른다.
 *
 * 명세에 없는 필드는 만들지 않는다(frontend-structure.md 7.3).
 */

/** 공통 규약 `Gender` */
export type Gender = 'MALE' | 'FEMALE' | 'OTHER';

/** 계정 상태. 인증을 마쳐야 `ACTIVE`가 되고 그전에는 로그인할 수 없다 */
export type UserStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'WITHDRAWN';

/** API-001 이메일 회원가입 요청 */
export type SignupRequest = {
  email: string;
  password: string;
  nickname: string;
  /** 생년월일 `YYYY-MM-DD`. 나이가 아니라 날짜다 */
  birthDate: string;
  gender: Gender;
};

/** API-001 응답 */
export type SignupResult = {
  userId: number;
  email: string;
  nickname: string;
  status: UserStatus;
};

/** API-002 이메일 인증 확인 응답 */
export type EmailVerificationResult = {
  userId: number;
  status: UserStatus;
};

/** 로그인 직후 화면 분기에 필요한 최소 정보 (API-004 응답의 `user`) */
export type AuthenticatedUser = {
  userId: number;
  nickname: string;
  /** `false`면 초기 취향 설정으로 보낸다 (API-004 처리 규칙) */
  onboardingCompleted: boolean;
};
