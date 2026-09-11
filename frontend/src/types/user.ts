/*
 * 사용자 타입. API-014 내 프로필 조회
 *
 * 홈(2.1)은 인사말에 쓰는 `nickname`만 썼다. MY 화면(업무 -305)이 나머지를 쓰기
 * 시작하면서 계약이 내려주는 필드를 채웠다.
 *
 * **필드 이름은 계약 응답 그대로다.** 업무 -364에서 이 도메인이 실제 API를 부르게
 * 바뀌었으므로(`api/users.ts`) 이름이 다르면 값이 `undefined`로 들어온다. 305 첫
 * 구현이 `joinedAt` · `ageGroup` · `avatarUrl`을 임의로 만들어 썼고, 2026-09-11
 * develop과 합치면서 계약(`docs/api/users.md` API-014)에 맞췄다.
 *
 * 공개 프로필(7.2-A · 7.2-B)과 취향 비교(7.2-C)는 API-062 · API-063이 있지만
 * **2차 MVP**라 아직 구현되지 않았다. 그 타입은 `types/unspecified.ts`에 둔다.
 */
import type { Visibility } from './enums';

export type MyProfileStats = {
  frameCount: number;
  popcornCount: number;
  ratingCount: number;
};

/** 성별. 가입 시 필수값이며 `null`로 되돌릴 수 없다(API-015 처리 규칙) */
export type Gender = 'MALE' | 'FEMALE' | 'OTHER';

export type MyProfile = {
  userId: number;
  /** 변경 불가(API-015 처리 규칙). 7.2가 읽기 전용으로 표시한다 */
  email: string;
  nickname: string;
  /** 생년월일 `YYYY-MM-DD`. 실명은 수집하지 않는다. 7.2는 연령대로 바꿔 보여준다 */
  birthDate: string;
  gender: Gender;
  /** 프로필 공개 범위. 기본값 `PRIVATE`. `PRIVATE`이면 API-062가 404다 */
  visibility: Visibility;
  hasPassword: boolean;
  /** 연결된 소셜 로그인 수단. 7.8-A 계정 관리가 쓴다 */
  linkedProviders: string[];
  onboardingCompleted: boolean;
  stats: MyProfileStats;
  /** 가입 시각. 7.1의 `2026.08 가입` */
  createdAt: string;
};

/**
 * 7.2가 저장하는 값. API-015는 전달한 필드만 바꾼다.
 *
 * **프로필 이미지와 한 줄 소개는 계약에 없다.** API-014 응답에도 API-015 요청에도
 * 그런 필드가 없다. 두 값은 `types/unspecified.ts`가 다룬다.
 */
export type MyProfileUpdate = {
  nickname?: string;
  birthDate?: string;
  gender?: Gender;
  visibility?: Visibility;
};
