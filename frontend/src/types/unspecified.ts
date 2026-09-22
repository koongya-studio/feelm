/*
 * 계약에 대응이 없는 화면 요소용 타입.
 *
 * 와이어프레임에는 있으나 API 계약에 데이터 출처가 없는 값이다. 계약이 다르게
 * 말하는 것이 아니라 아무 말도 하지 않는 경우이므로, 팀 결정(2026-09-07)에 따라
 * 와이어프레임대로 구현하고 여기에 모아 표시한다.
 *
 * 계약 타입(types/movie.ts · recommendation.ts · party.ts)에는 이 필드를 넣지
 * 않는다(frontend-structure.md 8.3). CR 결정이 나오면 이 파일만 보고 정리한다.
 *
 * | 필드 | 화면 | 상태 |
 * | --- | --- | --- |
 * | matchRate | 2.1 `취향 일치 92%` · 2.2 `일치 92%` `연결 72%` · 2.3-A `일치 87%` | CR-13 결정 대기 |
 * | runtimeMinutes | 2.1 `케이퍼 · 범죄 · 128분` | API-045 요약에 없음. API-030 상세에는 `runtimeMinutes`가 있음. CR 미등록 |
 * | newPopcornCount | 2.1 `멤버 4명 · 새 팝콘 2개` | API-050에 없음. 실제 연동(S15P21E106-393)으로 채울 값이 사라져 화면에서 뺐다. CR 미등록 |
 * | weeklyFrameDelta | 2.6 `99프레임 · 이번 주 +3` · 2.7 `100프레임 · 이번 주 +4` | 주간 증가 수를 내려주는 필드가 없음. API-014 `stats.frameCount`와 API-042 `totalCount`는 누적 수만 준다. CR 미등록 |
 * | ~~TasteFlowView~~ | 3.1 `요즘 취향 흐름` 문장 · 키워드 근거 문장 | **정리.** 화면에서 이미 빠졌고 부르는 곳 없이 남아 있던 목업 함수(`getTasteFlow`)와 타입을 지웠다 — 업무 -810. CR-16은 그대로 결정 대기 |
 * | 3.1-A 상영 시간 | 3.1-A `2013 · 케이퍼 · 115분` | API-043 `movie`에 없음. 실제 연동(S15P21E106-392)으로 채울 값이 사라져 화면에서 뺐다. CR 미등록 |
 * | ProfileView | 7.1 · 7.2 · 7.2-A · 7.2-B 프로필 | 7.2-B는 **실제 API-062**를 이 모양으로 바꿔 그린다(업무 -810). 7.1 · 7.2 · 7.2-A는 아직 내 `publicId`를 알 길이 없어 목업 기준값을 쓴다. 사진 · 소개 · 항목별 공개 여부는 계약에 아예 없다(업무 -616) |
 * | ~~ReportDetailView~~ | 3.3 · 3.4 · 7.4 국가 · 시대 비율 · 지난 반기 비교 % · 발급 요약 | **해소.** 2026-09-21 API-060 계약으로 들어와 `types/report.ts`로 옮겼다 — 업무 -558 |
 * | ~~ReportFourCutView~~ | 3.3 · 3.4 · 7.4 4컷의 사진 · 누끼 · 큰 글자 표기 · 감독 대표작 | **해소.** 자리는 계약에 들어왔다(`fourCut`). 계약이 주는 사진만 쓰고, 없는 칸은 이름만 그린다 — 업무 -558 |
 * | ~~OttRecommendationView~~ | EXT-01 ~ EXT-05 일치도 % · 이유 · 항목별 일치도 · 분석 요약 | **해소.** 화면에서 뺐다 — CR-28 · 업무 -563 |
 */
import type { RecommendedMovie } from './movie';

/** 추천 요약에 없는 상영 시간 */
export type UnspecifiedMovieFields = {
  runtimeMinutes: number;
};

export type RecommendedMovieView = RecommendedMovie & UnspecifiedMovieFields;

/** 2.6 · 2.7의 `이번 주 +N` */
export type WeeklyFrameDelta = {
  /** 이번 주에 추가된 프레임 수 */
  weeklyFrameDelta: number;
};

/*
 * ─────────────────────────────────────────── 프로필 (업무 -305 · -616)
 *
 * 7.1 MY · 7.2 편집 · 7.2-A 내 공개 프로필 미리보기 · 7.2-B 다른 사용자 공개 프로필이
 * **한 모양**을 쓴다(업무 -616, 사용자 지시 2026-09-18). 네 화면이 같은 `ProfileLayout`을
 * 그리고, 화면마다 다른 것은 데이터 가공이 아니라 `mode`와 권한이다.
 *
 * 본인의 값은 모든 키워드 · 필름을 `isPublic`과 함께 담는다. 공개 프로필(남 · 미리보기)은
 * 같은 모양에서 `isPublic`인 것만 남긴 것이다(`api/users.ts` `toPublicView`).
 *
 * **계약은 일부만 있다.** 7.2-B는 API-062 공개 프로필 조회(`docs/api/social.md`)를 부르고
 * 그 응답을 이 모양으로 바꾼다(`api/users.ts` `toPublicProfileView`, 업무 -810). **아래 셋은
 * 계약에 자리가 아예 없다.**
 * - `avatarUrl` 프로필 사진과 그 업로드 · 삭제
 * - `bio` 자기소개
 * - 키워드 · 필름마다의 `isPublic`
 * 화면은 목업 저장소(`mocks/sessionStore.ts`)에 쓰고 읽는다. 계약이 생기면 `api/users.ts`의
 * 함수 본문만 바꾼다.
 *
 * API-062와 다른 곳
 * - API-062는 `user` · `recentFrames` · `popcorn.flavors` · `publicReviewCount`로 내려준다.
 *   아래는 네 화면이 함께 쓰는 평평한 모양이라 `api/users.ts`가 바꿔 담는다
 * - API-062에는 한줄평 **목록**이 없다(개수만 있다). 그래서 7.2-B의 `reviews`는 `null`이다
 */

/** 프로필이 보여주는 수치. 팔로워 수 같은 개념은 Feelm에 없다 */
export type ProfileStats = {
  frameCount: number;
  popcornCount: number;
  /** 공개로 남긴 한줄평 수 */
  publicReviewCount: number;
};

/**
 * 프로필의 취향 키워드 하나. 공개 프로필에는 `isPublic`인 것만 온다.
 *
 * API-040 · API-062의 `keywords`와 같은 `code` · `label`이다. 한때 3.1 `요즘 취향 흐름`의 근거
 * 문장(`rationale`)까지 담았는데 그 카드가 화면에서 빠져 지웠다(업무 -810).
 */
export type ProfileKeyword = { code: string; label: string; isPublic: boolean };

/** 프로필의 취향 필름 한 칸. 공개 프로필에는 `isPublic`인 것만 온다 */
export type ProfileFrame = {
  frameId: number;
  movieId: number;
  title: string;
  posterUrl: string | null;
  isPublic: boolean;
};

/** 7.1 · 7.2 · 7.2-A · 7.2-B 프로필 */
export type ProfileView = {
  userId: number;
  nickname: string;
  /** 프로필 사진. 없으면 `null`이고 화면은 기본 이미지를 그린다 */
  avatarUrl: string | null;
  /** 자기소개. 비어 있을 수 있다 */
  bio: string;
  stats: ProfileStats;
  /** 취향 키워드. 앞쪽이 대표 키워드다 */
  keywords: ProfileKeyword[];
  /** 취향 필름. 최신순 */
  frames: ProfileFrame[];
  /** 팝콘 맛 분포. 코드와 알 수만 담고 색·이름은 API-044에서 온다 */
  popcorn: { flavorCode: string; popcornCount: number }[];
  /**
   * 공개 한줄평. **`null`이면 목록을 받을 수 없는 것이다** — API-062는 개수만 주고 사용자별
   * 공개 한줄평 목록 API는 계약에 없다(업무 -810). 빈 배열(공개한 것이 없음)과 다르다.
   */
  reviews:
    | {
        ratingId: number;
        movieTitle: string;
        comment: string;
        score: number;
      }[]
    | null;
};

/** 프로필 사진 · 소개 · 공개 항목 변경. 계약에 없다(위 머리말) */
export type ProfileExtrasUpdate = {
  avatarUrl?: string | null;
  bio?: string;
  publicKeywordCodes?: string[];
  publicFrameIds?: number[];
};
