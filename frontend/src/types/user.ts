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
  /** API-062 공유 주소에 사용하는 기존 계정 UUID */
  publicId: string;
  /** 변경 불가(API-015 처리 규칙). 7.2가 읽기 전용으로 표시한다 */
  email: string;
  nickname: string;
  /**
   * 프로필 사진(업무 -761 · -762). 없으면 `null`이다. 공개 · 비만료 경로(`/media/avatars/…`)이며
   * 사진을 바꾸거나 지우면 옛 주소는 404가 된다. 올리기 · 지우기는 API-081 · 082다
   */
  avatarUrl: string | null;
  /** 생년월일 `YYYY-MM-DD`. 실명은 수집하지 않는다. 7.2는 연령대로 바꿔 보여준다 */
  birthDate: string;
  gender: Gender;
  /** 프로필 공개 범위. 기본값 `PRIVATE`. `PRIVATE`이면 API-062가 404다 */
  visibility: Visibility;
  /**
   * 항목별 공개 범위(업무 -856, API-014 `항목별 공개 범위`). 기본값 `PUBLIC`. `PRIVATE`이면 다른
   * 사용자가 보는 API-062에서 그 구역이 비고, 한줄평은 API-032 목록에서도 빠진다. 7.8-B가 고른다
   */
  frameVisibility: Visibility;
  popcornVisibility: Visibility;
  reviewVisibility: Visibility;
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
 * 프로필 사진은 API-015가 아니라 따로 올리고 지운다(API-081 · 082). **한 줄 소개는 계약에
 * 없어** `types/unspecified.ts`가 다룬다.
 */
export type MyProfileUpdate = {
  nickname?: string;
  birthDate?: string;
  gender?: Gender;
  visibility?: Visibility;
  frameVisibility?: Visibility;
  popcornVisibility?: Visibility;
  reviewVisibility?: Visibility;
};

/**
 * API-016 닉네임 사용 가능 확인 응답.
 *
 * 형식 위반도 `400`이 아니라 `available: false`인 `200`이다. `reason`은 사용 가능하면 `null`이다.
 */
export type NicknameAvailability = {
  nickname: string;
  available: boolean;
  reason: 'DUPLICATED' | 'INVALID_FORMAT' | null;
};

/**
 * API-017 · API-018 알림 설정. 다섯 키가 모두 있어야 한다(API-018 `400 VALIDATION_FAILED`).
 *
 * 7.8-C의 토글 다섯 개가 계약 다섯 키와 하나씩 맞는다. 파티 초대는 업무 -857, 리포트 발급은
 * 업무 -858에서 계약에 들어왔다.
 */
export type NotificationSettings = {
  /** 감상 확인 알림 */
  RATING: boolean;
  RECOMMENDATION: boolean;
  /** 끄면 파티 초대 알림이 오지 않는다. 초대 자체는 초대 목록에 그대로 온다 */
  PARTY_INVITE: boolean;
  /** 끄면 반기 취향 리포트가 공개돼도 알림이 오지 않는다. 리포트는 그대로 볼 수 있다 */
  REPORT_ISSUED: boolean;
  MARKETING: boolean;
};

/** API-022 차단 목록 `items[]` */
export type BlockedUser = {
  userId: number;
  nickname: string;
  blockedAt: string;
};

/** API-022 응답 */
export type BlockedUserList = {
  items: BlockedUser[];
  nextCursor: string | null;
  hasNext: boolean;
};

/** API-020 응답 */
export type BlockResult = {
  blockedUserId: number;
  blockedAt: string;
};

/**
 * API-019 사용자 검색 `items[]`
 *
 * `visibility`가 함께 오는 것은 **`PRIVATE` 사용자도 결과에 들어오기 때문이다.** 가입
 * 기본값이 `PRIVATE`라 제외하면 닉네임 초대가 사실상 막힌다(API-019 처리 규칙). 화면은
 * 이 값으로 초대 후보 카드의 노출 수위를 정한다.
 */
export type SearchedUser = {
  userId: number;
  nickname: string;
  visibility: Visibility;
};

/** API-019 응답 */
export type SearchedUserList = {
  items: SearchedUser[];
  nextCursor: string | null;
  hasNext: boolean;
};

/**
 * API-062 공개 프로필 조회 응답 (업무 S15P21E106-810).
 *
 * 경로의 식별자는 `publicId`(UUID, ADR-0008)지만 본문의 `user.userId`는 내부 숫자 ID다. 차단
 * (API-020)이 이 값을 그대로 쓴다.
 *
 * `isBlockedByMe`는 계약에 있지만 **200 응답에서는 늘 `false`다** — 차단 관계는 어느 방향이든
 * 404로 감춘다(백엔드 `PublicProfileResponse` 주석). 화면은 이 값으로 아무것도 가르지 않는다.
 *
 * 한줄평은 **개수(`publicReviewCount`)만 온다.** 사용자별 공개 한줄평 목록 API는 계약에 없다.
 */
export type PublicProfile = {
  user: {
    userId: number;
    nickname: string;
    /** 등록한 사진이 없으면 `null` */
    avatarUrl: string | null;
    visibility: Visibility;
    /**
     * 항목별 공개 범위(업무 -856). 본인이 아니면 `PRIVATE`인 구역은 모양만 남고 값이 빈다 —
     * `recentFrames: []` · `frameCount: 0`, `flavors: []` · `popcornCount: 0`, `publicReviewCount: 0`.
     * 빈 것과 가린 것은 이 값으로 가른다
     */
    frameVisibility: Visibility;
    popcornVisibility: Visibility;
    reviewVisibility: Visibility;
  };
  keywords: { code: string; label: string }[];
  stats: { frameCount: number; popcornCount: number };
  popcorn: {
    flavors: { flavorCode: string; popcornCount: number; averageScore: number | null }[];
  };
  recentFrames: {
    frameId: number;
    movie: { movieId: number; title: string; posterUrl: string | null };
    score: number;
    createdAt: string;
  }[];
  publicReviewCount: number;
  isBlockedByMe: boolean;
};
