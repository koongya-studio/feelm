/*
 * 사용자 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md · docs/api/social.md이다.
 *
 * 목업을 걷어내고 실제 API를 부르는 첫 도메인이다(업무 S15P21E106-364).
 *
 * **한 파일에 실제 호출과 목업이 섞여 있다.**
 * - API-014 · API-015 · API-016 · API-019 · API-062 · API-081 · API-082는 실제로 부른다. 공개
 *   프로필(API-062)은 업무 -810, 프로필 사진(API-081 · 082)은 업무 -762에서 목업을 걷어냈다
 * - 자기소개 · 항목별 공개 여부는 **계약에 아예 없다**(업무 -616, `types/unspecified.ts`).
 *   세션 저장소에만 남는다. 내 프로필의 키워드 · 필름 목록도 목업 기준값에서 온다 — 내
 *   `publicId`를 알 길이 없어 내 공개 프로필을 부를 수 없다
 */
import { mockMyProfileView } from '../mocks/publicProfiles';
import { getProfileExtras, saveProfileExtras } from '../mocks/sessionStore';
import { readAsDataUrl } from '../utils/avatarImage';
import { request } from './client';
import { getMockProfile, isMockSession, saveMockProfile } from './mockAccount';
import { respond, respondError } from './mockTransport';
import type { ProfileExtrasUpdate, ProfileView } from '../types/unspecified';
import type {
  MyProfile,
  MyProfileUpdate,
  NicknameAvailability,
  PublicProfile,
  SearchedUserList,
} from '../types/user';

/**
 * API-014 내 프로필 조회 · GET /api/v1/users/me
 *
 * 회원 전용이다. 로그인하지 않았거나 세션이 끊겼으면 `401`이며, 호출한 화면이
 * 오류 상태로 처리한다.
 */
export async function getMyProfile(): Promise<MyProfile> {
  // 개발 서버의 목업 계정으로 로그인했으면 서버로 보내지 않는다(`mockAccount.ts`)
  if (isMockSession()) return respond(getMockProfile(), 'users');

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
  if (isMockSession()) return respond(saveMockProfile(update), 'users');

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
 * API-019 사용자 검색 · GET /api/v1/users/search
 *
 * **닉네임 접두 일치**다. 5.3이 파티에 초대할 사람을 고를 때 쓴다.
 *
 * 검색어는 정규화(공백 제거 · 소문자) 후 2~10자여야 하고 벗어나면
 * `400 VALIDATION_FAILED`다. 화면이 먼저 길이를 보고 부르지 않는 편이 낫다
 * (`isSearchableNickname`).
 *
 * 결과에서 빠지는 사람이 있다 — 본인, 차단 관계(양방향), 탈퇴 · 미인증 계정이다.
 * 차단은 계약이 **없는 사람처럼** 다루기로 했으므로 화면도 그 이유를 드러내지 않는다.
 * `PRIVATE` 사용자는 그대로 나오고 `visibility`가 함께 온다(API-019 처리 규칙).
 */
export async function searchUsers(nickname: string, cursor?: string): Promise<SearchedUserList> {
  return request<SearchedUserList>('/users/search', { query: { nickname, cursor } });
}

/**
 * 검색어로 보낼 수 있는 길이인지. API-019가 정규화 후 2~10자를 요구한다.
 *
 * 한 글자만 쳐도 요청이 나가면 매번 `400`을 받는다. 서버 규칙과 같은 정규화를 쓴다.
 */
export function isSearchableNickname(keyword: string): boolean {
  const normalized = keyword.replace(/\s/g, '');

  return normalized.length >= 2 && normalized.length <= 10;
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

/** 공개 프로필이 필름을 몇 개까지 보여주는지. 와이어프레임 7.2-A · 7.2-B가 3개를 그렸다 */
const PUBLIC_FRAME_COUNT = 3;

/** 자기소개 상한. 사용자 시안 7.2의 `16/40`이다. 계약이 없어 화면 시안을 따른다 */
export const BIO_MAX_LENGTH = 40;

/**
 * 내 프로필 전체(7.1 · 7.2). 모든 키워드 · 필름이 `isPublic`과 함께 온다.
 *
 * 닉네임 · 사진과 프레임 · 팝콘 수는 **실제 API-014**다. 나머지는 목업 기준값
 * (`mockMyProfileView`)에 바꾼 소개 · 공개 항목을 덮는다 — 그 둘은 계약에 자리가 없어 이번
 * 세션의 목업 저장소에서 온다.
 */
export async function getMyProfileView(): Promise<ProfileView> {
  return respond(toProfileView(await getMyProfile()), 'users');
}

function toProfileView(profile: MyProfile): ProfileView {
  const extras = getProfileExtras();
  const base = mockMyProfileView;

  return {
    ...base,
    userId: profile.userId,
    nickname: profile.nickname,
    avatarUrl: profile.avatarUrl,
    bio: extras.bio ?? base.bio,
    stats: {
      ...base.stats,
      frameCount: profile.stats.frameCount,
      popcornCount: profile.stats.popcornCount,
    },
    keywords: base.keywords.map((keyword) => ({
      ...keyword,
      isPublic: extras.publicKeywordCodes?.includes(keyword.code) ?? keyword.isPublic,
    })),
    frames: base.frames.map((frame) => ({
      ...frame,
      isPublic: extras.publicFrameIds?.includes(frame.frameId) ?? frame.isPublic,
    })),
  };
}

/**
 * 누구에게나 보이는 모양으로 줄인다. **7.2-A 미리보기와 7.2-B가 같은 결과를 보게 하는 곳이다.**
 *
 * 비공개 키워드 · 필름을 빼고 필름은 앞의 세 편만 남긴다. 남의 프로필은 서버(API-062)가
 * 이미 공개된 것만 주지만, 필름 개수는 같은 규칙으로 맞춘다.
 */
function toPublicView(profile: ProfileView): ProfileView {
  return {
    ...profile,
    keywords: profile.keywords.filter((keyword) => keyword.isPublic),
    frames: profile.frames.filter((frame) => frame.isPublic).slice(0, PUBLIC_FRAME_COUNT),
  };
}

/**
 * API-062 공개 프로필 조회 · GET /api/v1/users/{publicId}/profile — 7.2-B가 부른다(업무 -810).
 *
 * **인증은 선택이다.** 로그인했으면 토큰을 실어 차단 관계를 가리게 하고, 아니면 비회원으로
 * 부른다. 없는 사용자 · 비공개(`PRIVATE`) · 차단 관계는 모두 `404`로 합쳐 온다. 본인이 자기
 * `publicId`로 부르면 공개 범위와 무관하게 `200`이다.
 *
 * 경로의 `publicId`는 `users.public_id`(UUID, ADR-0008)다. 내부 숫자 ID는 공개 주소에 쓰지
 * 않는다. UUID가 아닌 값은 서버가 없는 사용자와 같게 `404`로 답한다.
 *
 * 응답은 네 프로필 화면이 함께 쓰는 모양(`ProfileView`)으로 바꿔 돌려준다(`toPublicProfileView`).
 */
export async function getPublicProfile(publicId: string): Promise<ProfileView> {
  const response = await request<PublicProfile>(`/users/${encodeURIComponent(publicId)}/profile`, {
    auth: 'optional',
  });

  return toPublicProfileView(response);
}

/**
 * API-062 응답을 프로필 화면의 모양으로 바꾼다.
 *
 * - 소개(`bio`)는 계약에 없어 빈 문자열이다. 화면은 빈 소개를 그리지 않는다
 * - 서버가 공개된 것만 주므로 키워드 · 필름은 모두 `isPublic`이다
 * - 한줄평은 개수만 오므로 목록은 `null`이다 — `공개한 한줄평이 없어요`와 구분한다
 */
function toPublicProfileView(response: PublicProfile): ProfileView {
  return toPublicView({
    userId: response.user.userId,
    nickname: response.user.nickname,
    avatarUrl: response.user.avatarUrl,
    bio: '',
    stats: {
      frameCount: response.stats.frameCount,
      popcornCount: response.stats.popcornCount,
      publicReviewCount: response.publicReviewCount,
    },
    keywords: response.keywords.map((keyword) => ({ ...keyword, isPublic: true })),
    frames: response.recentFrames.map((frame) => ({
      frameId: frame.frameId,
      movieId: frame.movie.movieId,
      title: frame.movie.title,
      posterUrl: frame.movie.posterUrl,
      isPublic: true,
    })),
    popcorn: response.popcorn.flavors.map(({ flavorCode, popcornCount }) => ({
      flavorCode,
      popcornCount,
    })),
    reviews: null,
  });
}

/**
 * 7.2-A 내 공개 프로필 미리보기. **7.2-B와 같은 공개 모양**(`toPublicView`)으로 준다.
 *
 * 내 프로필(API-014 + 목업 기준값)을 한 번 받아 줄인다. 본래는 내 `publicId`로 API-062를
 * 부르면 되지만, `publicId`를 알려 주는 API가 아직 없다(업무 -810). 생기면 이 본문을
 * `getPublicProfile(내 publicId)`로 바꾼다.
 */
export async function getMyPublicProfile(): Promise<ProfileView> {
  return toPublicView(await getMyProfileView());
}

/**
 * 자기소개 · 공개 키워드 · 공개 필름 저장. **계약이 없다**(업무 -616).
 *
 * 7.2는 항목별로 저장하므로 한 번에 한 키만 보낸다. 응답은 저장된 뒤의 내 프로필 전체다.
 * 세션 메모리라 새로고침하면 처음 값으로 돌아간다. 사진은 여기가 아니라 API-081 · 082다.
 */
export async function updateProfileExtras(update: ProfileExtrasUpdate): Promise<ProfileView> {
  if (update.bio !== undefined && [...update.bio].length > BIO_MAX_LENGTH) {
    return respondError(400, 'VALIDATION_FAILED', `소개는 ${BIO_MAX_LENGTH}자까지 쓸 수 있어요.`);
  }

  const profile = await getMyProfile();

  saveProfileExtras(update);

  return respond(toProfileView(profile), 'users');
}

/**
 * API-081 프로필 사진 등록 · 교체 · PUT /api/v1/users/me/avatar — 7.2가 부른다(업무 -762).
 *
 * multipart `file` 한 칸이다. 화면이 원 안을 512px JPEG로 잘라 보내지만, 서버도 내용을 다시
 * 판별해 가운데 기준 512×512 JPEG로 저장한다. 돌려받은 `avatarUrl`은 매번 새 주소다.
 *
 * 오류는 `400 AVATAR_INVALID`(읽을 수 없거나 받지 않는 형식) · `413 AVATAR_TOO_LARGE`(10MiB
 * 초과) · `503 AVATAR_STORAGE_UNAVAILABLE`이다. 실패하면 서버는 기존 사진을 그대로 둔다.
 */
export async function uploadMyAvatar(photo: Blob): Promise<string> {
  if (isMockSession()) {
    // 목업 계정은 서버에 올리지 않는다. 탭 저장소에 넣을 수 있게 글자로 바꿔 둔다
    const dataUrl = await readAsDataUrl(photo);
    saveMockProfile({ avatarUrl: dataUrl });

    return respond(dataUrl, 'users');
  }

  const body = new FormData();
  body.append('file', photo, 'avatar.jpg');

  const { avatarUrl } = await request<{ avatarUrl: string }>('/users/me/avatar', {
    method: 'PUT',
    body,
  });

  return avatarUrl;
}

/**
 * API-082 프로필 사진 삭제 · DELETE /api/v1/users/me/avatar — 7.2의 `기본 이미지` · `현재 사진
 * 삭제`가 부른다. 사진이 없어도 성공(204)이다. 지운 뒤 API-014 · 062의 `avatarUrl`은 `null`이다.
 */
export async function deleteMyAvatar(): Promise<void> {
  if (isMockSession()) {
    saveMockProfile({ avatarUrl: null });

    return respond(undefined, 'users');
  }

  await request<void>('/users/me/avatar', { method: 'DELETE' });
}
