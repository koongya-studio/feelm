/*
 * 사용자 데이터 접근 함수.
 * 대응 명세는 docs/api/users.md · docs/api/social.md이다.
 *
 * 목업을 걷어내고 실제 API를 부르는 첫 도메인이다(업무 S15P21E106-364).
 *
 * **한 파일에 실제 호출과 목업이 섞여 있다.** 갈리는 기준은 계약의 단계다.
 * - API-014 · API-015 · API-016 · API-019는 1차 MVP라 실제로 부른다
 * - API-062(공개 프로필)는 **2차 MVP라 아직 없다.** MY 화면 업무(-305)가 화면을 먼저
 *   만들었으므로 목업으로 응답한다. 계약이 열리면 `getPublicProfile` 본문만 `request`로 바꾼다
 * - 프로필 사진 · 자기소개 · 항목별 공개 여부는 **계약에 아예 없다**(업무 -616,
 *   `types/unspecified.ts`). 세션 저장소에만 남는다
 */
import { findMockPublicProfile, mockMyProfileView } from '../mocks/publicProfiles';
import { getProfileExtras, saveProfileExtras } from '../mocks/sessionStore';
import { request } from './client';
import { getMockProfile, isMockSession, saveMockProfile } from './mockAccount';
import { respond, respondError } from './mockTransport';
import type { ProfileExtrasUpdate, ProfileView } from '../types/unspecified';
import type {
  MyProfile,
  MyProfileUpdate,
  NicknameAvailability,
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
 * 닉네임과 프레임 · 팝콘 수는 **실제 API-014**다. 나머지는 목업 기준값(`mockMyProfileView`)에
 * 이번 세션에 바꾼 사진 · 소개 · 공개 항목을 덮는다 — 그 셋은 계약에 자리가 없다.
 */
export async function getMyProfileView(): Promise<ProfileView> {
  const profile = await getMyProfile();
  const extras = getProfileExtras();
  const base = mockMyProfileView;

  return respond(
    {
      ...base,
      userId: profile.userId,
      nickname: profile.nickname,
      avatarUrl: extras.avatarUrl === undefined ? base.avatarUrl : extras.avatarUrl,
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
    },
    'users',
  );
}

/**
 * 누구에게나 보이는 모양으로 줄인다. **7.2-A 미리보기와 7.2-B가 같은 결과를 보게 하는 곳이다.**
 *
 * 비공개 키워드 · 필름을 빼고 필름은 앞의 세 편만 남긴다. 계약이 열리면 서버가 이 일을 한다.
 */
function toPublicView(profile: ProfileView): ProfileView {
  return {
    ...profile,
    keywords: profile.keywords.filter((keyword) => keyword.isPublic),
    frames: profile.frames.filter((frame) => frame.isPublic).slice(0, PUBLIC_FRAME_COUNT),
  };
}

/**
 * API-062 공개 프로필 조회 · GET /api/v1/users/{publicId}/profile — 7.2-B와 7.2-A가 함께 쓴다.
 *
 * **2차 MVP라 아직 없다.** 계약은 본인이 자신을 조회하면 `visibility`와 무관하게 `200`을
 * 준다. 목업도 같다 — 목업 사용자에 없는 번호면 내 번호인지 보고, 맞으면 내 프로필을 공개
 * 모양으로 줄여 준다. 그래서 7.2에서 바꾼 값이 7.2-A에 바로 보인다.
 *
 * `[!]` 계약의 경로 파라미터는 `publicId`(`users.public_id`, ADR-0008)다. **내부 숫자
 * ID를 공개 URL에 노출하지 않는다는 결정**이므로, 계약이 열릴 때 라우트도
 * `/users/:publicId`로 바꿔야 한다. 목업은 아직 숫자 id로 찾는다.
 */
export async function getPublicProfile(userId: number): Promise<ProfileView> {
  const profile = findMockPublicProfile(userId);

  if (profile) return respond(toPublicView(profile), 'users');

  /*
   * 계약은 없음 · PRIVATE · 차단 관계를 모두 404로 합친다. 차단 관계는 실제 API-062가
   * `isBlockedByMe`로 판정할 일이라, 목업 프로필이 실제 차단 목록(API-022)을 섞어 보지
   * 않는다(업무 -610).
   */
  const mine = await getMyProfileView();

  if (mine.userId === userId) return toPublicView(mine);

  return respondError(404, 'RESOURCE_NOT_FOUND', '프로필을 찾을 수 없습니다.');
}

/**
 * 7.2-A 내 공개 프로필 미리보기. **7.2-B와 같은 공개 모양**(`toPublicView`)으로 준다.
 *
 * `getPublicProfile(내 번호)`와 결과가 같지만, 그 길은 내 번호를 알려고 API-014를 한 번,
 * 내 프로필을 만들려고 또 한 번 불러 같은 요청이 두 번 나갔다(!402 리뷰). 여기서는 내 프로필을
 * 한 번만 받아 줄인다. 계약(API-062)이 열리면 내 `publicId`로 그 API를 부르면 된다.
 */
export async function getMyPublicProfile(): Promise<ProfileView> {
  return toPublicView(await getMyProfileView());
}

/**
 * 프로필 사진 · 자기소개 · 공개 키워드 · 공개 필름 저장. **계약이 없다**(업무 -616).
 *
 * 7.2는 항목별로 저장하므로 한 번에 한 키만 보낸다. 사진은 화면이 잘라 만든 data URL이고
 * `null`이면 기본 이미지로 돌아간다. 응답은 저장된 뒤의 내 프로필 전체다.
 */
export async function updateProfileExtras(update: ProfileExtrasUpdate): Promise<ProfileView> {
  if (update.bio !== undefined && [...update.bio].length > BIO_MAX_LENGTH) {
    return respondError(400, 'VALIDATION_FAILED', `소개는 ${BIO_MAX_LENGTH}자까지 쓸 수 있어요.`);
  }

  await respond(undefined, 'users');
  saveProfileExtras(update);

  return getMyProfileView();
}
