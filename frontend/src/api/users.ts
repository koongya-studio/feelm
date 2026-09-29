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
 *   세션 저장소에만 남는다. 내 프로필의 키워드 · 필름 목록은 API-040 · API-042로 받는다
 */
import { mockMyProfileView } from '../mocks/publicProfiles';
import { mockFrameList } from '../mocks/frames';
import { getProfileExtras, saveProfileExtras } from '../mocks/sessionStore';
import { getMyFrames, getMyTaste } from './taste';
import { readAsDataUrl } from '../utils/avatarImage';
import { request } from './client';
import type { ProfileReviewPage } from '../types/profileReviews';
import { getMockProfile, isMockSession, saveMockProfile } from './mockAccount';
import { respond, respondError } from './mockTransport';
import type { FrameSummary, TasteAnalysis, TasteKeyword } from '../types/taste';
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
 * 닉네임 · 사진과 프레임 · 팝콘 수는 **실제 API-014**, 키워드는 **API-040**, 필름은 **API-042**다
 * (업무 S15P21E106-836). 나머지는 목업 기준값(`mockMyProfileView`)에 바꾼 소개 · 공개 항목을
 * 덮는다 — 그 둘은 계약에 자리가 없어 이번 세션의 목업 저장소에서 온다.
 *
 * **키워드를 못 받아도 프로필은 준다.** 취향 분석이 실패했다고 프로필 화면 전체를 막을 이유가
 * 없다. 그때는 키워드가 빈 배열이고 화면이 빈 상태 문구를 그린다.
 *
 * 취향 분석을 이미 부른 쪽은 그 요청을 `taste`로 넘긴다. 7.1은 키워드와 평가 수를 따로
 * 쓰느라 API-040을 직접 부르는데, 여기서 또 부르면 들어올 때마다 같은 요청이 두 번 나갔다
 * (업무 S15P21E106-893).
 */
export async function getMyProfileView(
  taste?: Promise<TasteAnalysis | null>,
): Promise<ProfileView> {
  if (isMockSession()) {
    const analysis = await (taste ?? getMyTaste().catch(() => null));
    return respond(
      toProfileView(getMockProfile(), analysis?.keywords ?? [], mockFrameList.items),
      'users',
    );
  }

  const [profile, analysis, frames] = await Promise.all([
    getMyProfile(),
    taste ?? getMyTaste().catch(() => null),
    getProfileFrames(),
  ]);

  return respond(toProfileView(profile, analysis?.keywords ?? [], frames), 'users');
}

/** 공개할 영화를 고르도록 최신순의 모든 페이지를 받는다. 실패는 화면으로 전달한다. */
async function getProfileFrames(): Promise<FrameSummary[]> {
  const frames: FrameSummary[] = [];
  const cursors = new Set<string>();
  let cursor: string | undefined;

  for (;;) {
    const page = await getMyFrames(cursor);
    frames.push(...page.items);
    if (!page.hasNext) return frames;
    if (!page.nextCursor || cursors.has(page.nextCursor)) {
      throw new Error('필름 목록의 다음 페이지를 확인하지 못했어요');
    }
    cursors.add(page.nextCursor);
    cursor = page.nextCursor;
  }
}

function toProfileView(
  profile: MyProfile,
  keywords: TasteKeyword[],
  frames: FrameSummary[],
): ProfileView {
  const extras = getProfileExtras();
  const base = mockMyProfileView;

  return {
    ...base,
    userId: profile.userId,
    publicId: profile.publicId,
    nickname: profile.nickname,
    avatarUrl: profile.avatarUrl,
    bio: extras.bio ?? base.bio,
    stats: {
      ...base.stats,
      frameCount: profile.stats.frameCount,
      popcornCount: profile.stats.popcornCount,
    },
    keywords: keywords.map((keyword) => ({
      ...keyword,
      // 저장된 공개 목록에 없는 코드는 공개로 본다(업무 -836). 평가할 때마다 키워드가 달라지는데
      // 기본을 비공개로 두면 서버가 새로 만들어 준 키워드가 공개 프로필 어디에도 안 보인다.
      isPublic: extras.publicKeywordCodes?.includes(keyword.code) ?? true,
    })),
    frames: frames.map((frame) => ({
      frameId: frame.frameId,
      movieId: frame.movie.movieId,
      title: frame.movie.title,
      posterUrl: frame.movie.posterUrl,
      isPublic: extras.publicFrameIds?.includes(frame.frameId) ?? true,
    })),
    hidden: hiddenSections(profile),
  };
}

/**
 * 항목별 공개 범위(업무 -856)에서 가려지는 구역. API-014와 API-062의 `user`가 같은 세 이름을 준다.
 */
function hiddenSections(
  owner: Pick<MyProfile, 'frameVisibility' | 'popcornVisibility' | 'reviewVisibility'>,
): ProfileView['hidden'] {
  return {
    frames: owner.frameVisibility === 'PRIVATE',
    popcorn: owner.popcornVisibility === 'PRIVATE',
    reviews: owner.reviewVisibility === 'PRIVATE',
  };
}

/**
 * 누구에게나 보이는 모양으로 줄인다. **7.2-A 미리보기와 7.2-B가 같은 결과를 보게 하는 곳이다.**
 *
 * 비공개 키워드 · 필름을 빼고 필름은 앞의 세 편만 남긴다. 남의 프로필은 서버(API-062)가
 * 이미 공개된 것만 주지만, 필름 개수는 같은 규칙으로 맞춘다.
 *
 * 항목별 공개 범위(업무 -856)가 비공개인 구역은 비운다. 서버가 타인에게 주는 모양(API-062 처리
 * 규칙)과 같게 해 두어, 미리보기가 내 값을 그대로 새어 보이지 않게 한다.
 */
function toPublicView(profile: ProfileView): ProfileView {
  const { hidden } = profile;

  return {
    ...profile,
    stats: {
      frameCount: hidden.frames ? 0 : profile.stats.frameCount,
      popcornCount: hidden.popcorn ? 0 : profile.stats.popcornCount,
      publicReviewCount: hidden.reviews ? 0 : profile.stats.publicReviewCount,
    },
    keywords: profile.keywords.filter((keyword) => keyword.isPublic),
    frames: hidden.frames
      ? []
      : profile.frames.filter((frame) => frame.isPublic).slice(0, PUBLIC_FRAME_COUNT),
    popcorn: hidden.popcorn ? [] : profile.popcorn,
    reviews: hidden.reviews ? [] : profile.reviews,
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

  return { ...toPublicProfileView(response), publicId };
}

/**
 * API-062 응답을 프로필 화면의 모양으로 바꾼다.
 *
 * - 소개(`bio`)는 계약에 없어 빈 문자열이다. 화면은 빈 소개를 그리지 않는다
 * - 서버가 공개된 것만 주므로 키워드 · 필름은 모두 `isPublic`이다
 * - 이 응답의 한줄평은 개수만 온다. 목록은 ProfileReviews가 API-087로 별도 조회한다
 * - 가려진 구역(업무 -856)은 `user`의 세 공개 범위로 안다. 서버가 비워 준 값을 그대로 담는다
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
    hidden: hiddenSections(response.user),
  });
}

/**
 * 7.2-A 내 공개 프로필 미리보기. **7.2-B와 같은 공개 모양**(`toPublicView`)으로 준다.
 *
 * 내 프로필(API-014 + 목업 기준값)을 한 번 받아 줄인다. 본래는 내 `publicId`로 API-062를
 * 부를 수도 있지만, 서버의 본인 조회는 비공개 구역도 반환하므로 미리보기는 공개 범위를
 * 적용하는 이 변환을 유지한다.
 */
export async function getMyPublicProfile(): Promise<ProfileView> {
  const profile = toPublicView(await getMyProfileView());
  if (!profile.publicId || isMockSession()) return profile;
  const publicProfile = await getPublicProfile(profile.publicId);
  return {
    ...profile,
    stats: {
      ...profile.stats,
      publicReviewCount: profile.hidden.reviews ? 0 : publicProfile.stats.publicReviewCount,
    },
  };
}

/** API-087. 선택 인증 및 공개 프로필과 동일한 접근 정책. */
export function getProfileReviews(
  publicId: string,
  cursor?: string,
  size = 10,
): Promise<ProfileReviewPage> {
  return request(`/users/${encodeURIComponent(publicId)}/reviews`, {
    auth: 'optional',
    query: { cursor, size },
  });
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

  const [profile, taste, frames] = await Promise.all([
    getMyProfile(),
    getMyTaste().catch(() => null),
    getProfileFrames(),
  ]);

  saveProfileExtras(update);

  return respond(toProfileView(profile, taste?.keywords ?? [], frames), 'users');
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
