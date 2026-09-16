/*
 * 사용자 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md · docs/api/social.md이다.
 *
 * 목업을 걷어내고 실제 API를 부르는 첫 도메인이다(업무 S15P21E106-364).
 *
 * **한 파일에 실제 호출과 목업이 섞여 있다.** 갈리는 기준은 계약의 단계다.
 * - API-014 · API-015는 1차 MVP라 실제로 부른다
 * - API-062 · API-063(공개 프로필 · 취향 비교)은 **2차 MVP라 아직 없다.** MY 화면
 *   업무(-305)가 화면을 먼저 만들었으므로 목업으로 응답한다. 계약이 열리면 이 파일의
 *   두 함수 본문만 `request`로 바꾸면 된다
 * - 한 줄 소개는 **계약에 아예 없다**(`types/unspecified.ts`). 세션 저장소에만 남는다
 */
import {
  findMockPublicProfile,
  findMockTasteCompare,
  mockMyPublicProfile,
} from '../mocks/publicProfiles';
import { getProfileEdit, saveProfileEdit } from '../mocks/sessionStore';
import { mockMyIntroduction } from '../mocks/user';
import { request } from './client';
import { isBlockedUser } from './settings';
import { respond, respondError } from './mockTransport';
import type { PublicProfileView, TasteCompareView } from '../types/unspecified';
import type { MyProfile, MyProfileUpdate, NicknameAvailability } from '../types/user';

/**
 * API-014 내 프로필 조회 · GET /api/v1/users/me
 *
 * 회원 전용이다. 로그인하지 않았거나 세션이 끊겼으면 `401`이며, 호출한 화면이
 * 오류 상태로 처리한다.
 */
export async function getMyProfile(): Promise<MyProfile> {
  return request<MyProfile>('/users/me');
}

/**
 * API-015 내 프로필 수정 · PATCH /api/v1/users/me
 *
 * 전달한 필드만 바꾼다. 응답은 API-014와 같은 본문이라 화면이 그대로 쓴다.
 *
 * 7.2 저장이 호출한다. **세션 저장소에 쓰지 않는다** — 조회가 실제 API라 로컬에만
 * 저장하면 7.1로 돌아갔을 때 옛 닉네임이 그대로 보인다(이동표 `7.2 ⇢ 7.1`).
 *
 * 닉네임 중복은 `409 NICKNAME_DUPLICATED`, 형식 오류는 `400 VALIDATION_FAILED`다.
 * 화면은 `ApiError`의 메시지를 그대로 보여준다.
 */
export async function updateMyProfile(update: MyProfileUpdate): Promise<MyProfile> {
  return request<MyProfile>('/users/me', { method: 'PATCH', body: update });
}

/**
 * API-016 닉네임 사용 가능 확인 · GET /api/v1/users/nickname-availability
 *
 * 공개 API다. 0.4 `중복 확인`이 부른다. 확인 결과는 예약이 아니라 가입(API-001)이 다시
 * 검사하므로, 확인을 통과해도 가입에서 `409 NICKNAME_DUPLICATED`가 올 수 있다.
 */
export async function checkNicknameAvailability(nickname: string): Promise<NicknameAvailability> {
  return request<NicknameAvailability>('/users/nickname-availability', {
    query: { nickname },
    auth: 'none',
  });
}

/**
 * 7.2의 닉네임 규칙. 가입(API-001)과 같다 — 2~10자, 한글 · 영문 · 숫자 · `_`.
 *
 * 서버도 검사하지만(API-015) 눌러 보기 전에 알려주려고 화면에서도 확인한다.
 * 와이어프레임 7.2의 헬퍼 문구는 `한글, 영문, 숫자 2~10자`로 `_`를 빠뜨렸다.
 * **계약이 기준이다**(screen-spec.md 2장).
 */
export function isValidNickname(nickname: string): boolean {
  return /^[가-힣a-zA-Z0-9_]{2,10}$/.test(nickname);
}

/**
 * 내 한 줄 소개.
 *
 * **계약에 없는 필드다**(`types/unspecified.ts`). API-014 응답에도 API-015 요청에도
 * 자리가 없어 세션 저장소에만 남는다. 새로고침하면 사라진다.
 */
export async function getMyIntroduction(): Promise<string> {
  return respond(getProfileEdit()?.introduction ?? mockMyIntroduction);
}

/** 한 줄 소개 저장. 계약이 없으므로 서버로 나가지 않는다 */
export async function saveMyIntroduction(introduction: string): Promise<void> {
  saveProfileEdit({ introduction });
}

/**
 * API-062 공개 프로필 조회 · GET /api/v1/users/{publicId}/profile — 본인(7.2-A).
 *
 * **2차 MVP라 아직 없다.** 계약은 본인이 자신을 조회하면 `visibility`와 무관하게
 * 200을 준다.
 *
 * `[!]` **응답 모양이 계약과 다르다.** API-062는 `user` · `recentFrames` ·
 * `popcorn.flavors` · `publicReviewCount` · `isBlockedByMe`로 내려주는데, 305는 계약을
 * 찾기 전에 화면 기준으로 만들었다. 계약이 열릴 때 맞춘다(screen-spec-my.md).
 */
export async function getMyPublicProfile(): Promise<PublicProfileView> {
  const profile = await getMyProfile();

  return respond({
    ...mockMyPublicProfile,
    userId: profile.userId,
    nickname: profile.nickname,
    introduction: getProfileEdit()?.introduction ?? mockMyIntroduction,
    stats: {
      ...mockMyPublicProfile.stats,
      frameCount: profile.stats.frameCount,
      popcornCount: profile.stats.popcornCount,
    },
  });
}

/**
 * API-062 공개 프로필 조회 — 다른 사용자(7.2-B). **2차 MVP라 아직 없다.**
 *
 * `[!]` 계약의 경로 파라미터는 `publicId`(`users.public_id`, ADR-0008)다. **내부 숫자
 * ID를 공개 URL에 노출하지 않는다는 결정**이므로, 계약이 열릴 때 라우트도
 * `/users/:publicId`로 바꿔야 한다. 목업은 아직 숫자 id로 찾는다.
 */
export async function getPublicProfile(userId: number): Promise<PublicProfileView> {
  const profile = findMockPublicProfile(userId);

  /*
   * 차단한 사람의 프로필은 보이지 않는다(API-020 설명). 계약은 없음 · 비공개 · 차단을
   * 모두 404로 합치므로 같은 응답을 쓴다. 7.2-B에서 차단하면 이 분기로 들어온다(업무 -307).
   *
   * 차단 여부는 실제 차단 목록(API-022)에서 온다(업무 -394). 프로필 자체가 목업이라
   * 남은 판정이며, API-062가 열리면 서버의 `isBlockedByMe`가 대신한다.
   */
  if (!profile || (await isBlockedUser(userId))) {
    // 계약은 없음 · PRIVATE · 차단 관계를 모두 404로 합친다
    return respondError(404, 'RESOURCE_NOT_FOUND', '프로필을 찾을 수 없습니다.');
  }

  return respond(profile, 'users');
}

/**
 * API-063 취향 비교 · GET /api/v1/users/{publicId}/taste-comparison
 *
 * **2차 MVP라 아직 없다.** 7.2-C가 쓴다. `publicId` 문제는 위와 같다.
 * 자기 자신과의 비교는 `400 CANNOT_COMPARE_SELF`다.
 *
 * `[!]` **응답 모양이 계약과 다르다.** API-063은 `similarity`(0~1) · `sharedFlavors` ·
 * `flavorComparison`(맛별 내 것 / 상대 것) · `commonMovies`로 내려주는데, 화면은
 * 와이어프레임대로 장르 · 국가 · 시대 · 감독 4줄을 그린다. **계약에는 그 4줄에 해당하는
 * 값이 없다.** 계약이 열릴 때 화면을 맛 기준 비교로 바꿔야 한다(screen-spec-my.md).
 */
export async function getTasteCompare(userId: number): Promise<TasteCompareView> {
  const profile = await getMyProfile();

  if (userId === profile.userId) {
    return respondError(400, 'CANNOT_COMPARE_SELF', '자기 자신과는 비교할 수 없습니다.');
  }

  const compare = findMockTasteCompare(userId);

  if (!compare) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '취향 비교 결과를 찾을 수 없습니다.');
  }

  // 내 닉네임은 실제 프로필에서 온다
  return respond({
    ...compare,
    me: { userId: profile.userId, nickname: profile.nickname, avatarUrl: null },
  });
}
