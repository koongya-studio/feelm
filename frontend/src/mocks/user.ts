/*
 * 목업 사용자 프로필. API-014 내 프로필 조회
 *
 * 닉네임은 와이어프레임 2.1의 인사말 `김난다 님, 오늘은 어떤 영화를 볼까요?`에서
 * 가져왔다. 측정된 값이 아니라 목업이다(screen-spec.md 3.3).
 *
 * 이메일 · 가입 연월 · 성별은 와이어프레임 7.1 · 7.2에서 옮겼다. 연령대(`20대`)는
 * 계약에 없는 개념이라 생년월일로 두고 화면이 바꿔 보여준다.
 *
 * API-014가 실제 호출로 바뀌어 평소에는 응답에 쓰이지 않는다(`api/users.ts`). 공개 프로필
 * 목업(`publicProfiles.ts`)이 기준값으로 읽고, 개발 서버의 목업 계정(`api/mockAccount.ts`)으로
 * 로그인했을 때만 내 프로필 응답이 된다.
 *
 * **수치는 100 기준으로 통일한다.** 와이어프레임 7.1은 `본 영화 13 · 평가 10`을
 * 적었지만 같은 사용자의 7.2-A는 `필름 100프레임 · 팝콘 100알`이고 3.1 · 3.2도 100이다.
 * 7.1 쪽이 다른 화면과 어긋나므로 100을 따른다(screen-spec-my.md 3.1).
 */
import type { MyProfile } from '../types/user';

export const mockMyProfile: MyProfile = {
  userId: 1024,
  publicId: '1b4e28ba-2fa1-11d2-883f-0016d3cca427',
  email: 'nanda@feelm.kr',
  nickname: '김난다',
  avatarUrl: null,
  // 와이어프레임 7.2가 `20대`로 표시한다. 계약은 생년월일을 주고 화면이 연령대로 바꾼다
  birthDate: '1999-03-14',
  gender: 'FEMALE',
  visibility: 'PUBLIC',
  frameVisibility: 'PUBLIC',
  popcornVisibility: 'PUBLIC',
  reviewVisibility: 'PUBLIC',
  hasPassword: true,
  linkedProviders: [],
  onboardingCompleted: true,
  // 와이어프레임 3.1 취향 필름이 표시하는 기준 수치와 맞춘다
  stats: { frameCount: 100, popcornCount: 100, ratingCount: 100 },
  // 와이어프레임 7.1의 `2026.08 가입`
  createdAt: '2026-08-14T09:00:00Z',
};
