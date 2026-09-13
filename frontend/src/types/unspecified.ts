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
 * | AvailableNowMovie | 2.1 `내 OTT에서 지금 볼 수 있어요` 4편 | CR-14 결정 대기. 응답 형태 자체가 미정 |
 * | newPopcornCount | 2.1 `멤버 4명 · 새 팝콘 2개` | API-050에 없음. CR 미등록 |
 * | weeklyFrameDelta | 2.6 `99프레임 · 이번 주 +3` · 2.7 `100프레임 · 이번 주 +4` | 주간 증가 수를 내려주는 필드가 없음. API-014 `stats.frameCount`와 API-042 `totalCount`는 누적 수만 준다. CR 미등록 |
 * | TasteFlowView.flowSentence | 3.1 `스릴러에서 느와르로 넓어지고 있어요` | CR-16 결정 대기. 대응 필드가 없음 |
 * | TasteKeywordView.rationale | 3.1 `계획형 캐릭터 영화 12편에 ★4 이상` 등 4개 | CR-16 결정 대기. API-040 `keywords`는 `code`·`label`만 있음 |
 * | FrameDetailView.movie.runtimeMinutes | 3.1-A `2013 · 케이퍼 · 115분` | API-043 `movie`에 없음. runtimeMinutes와 같은 항목이므로 CR 미등록 |
 * | PartyTasteView.tasteShare | 5.9 `액션 · 모험` `46%` · `스릴러 · 범죄` `38%` | CR-17 결정 대기. 무엇의 비율인지 정의가 없고 API-057 집계로 재현되지 않음 |
 * | PartyTasteView.tags | 5.9 `케이퍼` `빠른 전개` `반전` `느와르` | CR-17 결정 대기. API-044 `representativeKeywords`나 API-040 `keywords`로 보이나 값이 맞지 않음 |
 * | PartyMixView.commonRate · tags | 5.9 `공통 취향 일치 72%` · `스릴러` `빠른 전개` | CR-17 결정 대기. 산출 규칙이 계약에 없음 |
 * | PublicProfileView | 7.2-A · 7.2-B 공개 프로필 | API-062가 있으나 **2차 MVP**라 미구현. 응답 모양도 다름 |
 * | TasteCompareView | 7.2-C `76%` · 장르 82 · 국가 74 · 시대 71 · 감독 68 | API-063이 있으나 **2차 MVP**라 미구현. **4줄 비교는 계약에 없음** |
 * | introduction | 7.2 한 줄 소개 · 7.2-A · 7.2-B 소개 문구 | 와이어프레임에 없고 레퍼런스 지시로 추가. 계약에 자리가 없음. CR 미등록 |
 * | RecommendationHistoryItemView.ott | 7.5 `8. 24 · 넷플릭스` | API-048 `movie`에 OTT가 없음. 제공처가 여럿일 때 기준도 없음. CR 미등록 |
 */
import type { FrameDetail } from './taste';
import type { Party } from './party';
import type { RecommendedMovie } from './movie';
import type {
  DismissResult,
  RecommendationHistory,
  RecommendationHistoryItem,
  RecommendationHistorySet,
  RecommendationItem,
  RecommendationSet,
} from './recommendation';

/** 추천 요약에 없는 상영 시간 */
export type UnspecifiedMovieFields = {
  runtimeMinutes: number;
};

export type RecommendedMovieView = RecommendedMovie & UnspecifiedMovieFields;

/** 추천 항목 + 일치도 수치 */
export type RecommendationItemView = Omit<RecommendationItem, 'movie'> & {
  movie: RecommendedMovieView;
  /** 일치도 백분율. TASTE는 `일치`, DISCOVERY는 `연결`로 표시한다 */
  matchRate: number;
};

export type RecommendationSetView = Omit<RecommendationSet, 'items'> & {
  items: RecommendationItemView[];
};

export type DismissResultView = Omit<DismissResult, 'replacement'> & {
  replacement: RecommendationItemView | null;
};

/** 2.1 `내 OTT에서 지금 볼 수 있어요` 목록 항목 */
export type AvailableNowMovie = {
  movieId: number;
  title: string;
  posterUrl: string | null;
  /** 이 영화를 볼 수 있는 구독 중 OTT 코드 하나 */
  ottProvider: string;
};

/** 파티 요약 + 새 팝콘 수 */
export type PartyView = Party & {
  newPopcornCount: number;
};

/** 2.6 · 2.7의 `이번 주 +N` */
export type WeeklyFrameDelta = {
  /** 이번 주에 추가된 프레임 수 */
  weeklyFrameDelta: number;
};

/**
 * 3.1 취향 키워드 한 개.
 *
 * `label`은 API-040 `keywords[].label`에 대응하지만, 2.1은 같은 키워드를
 * `계획형 안티히어로를 좋아해요`라는 문장으로 보여주고 3.1은 `계획형 안티히어로`라는
 * 명사형 칩으로 보여준다. 계약에는 `label` 하나뿐이라 한 배열로 두 표기를 담을 수
 * 없으므로, 근거 문장(계약에 없음)과 함께 3.1 전용 값으로 분리했다(팀 결정 2026-09-07).
 * API-040의 키워드 사전이 확정되면 두 화면이 `keywords`를 함께 읽도록 정리한다.
 */
export type TasteKeywordView = {
  code: string;
  /** 칩에 표시하는 명사형 라벨 */
  label: string;
  /** 라벨 옆 근거 문장 */
  rationale: string;
};

/** 3.1 `요즘 취향 흐름` 카드 */
export type TasteFlowView = {
  /** 흐름 한 문장 */
  flowSentence: string;
  /** 와이어프레임은 4개다. API-040 `keywords`는 3~5개 */
  keywords: TasteKeywordView[];
};

/** 프레임 상세 + 상영 시간 */
export type FrameDetailView = Omit<FrameDetail, 'movie'> & {
  movie: FrameDetail['movie'] & UnspecifiedMovieFields;
};

/**
 * 5.9 참여자 한 명의 취향 수치.
 *
 * 5.8의 `대표 맛`과 `겹치는 맛`은 API-057의 맛별 집계로 계산할 수 있어 화면에서
 * 계산한다. 반면 5.9의 `46%` · `38%`는 무엇의 비율인지 정의가 없고 집계로도 재현되지
 * 않아 값 자체를 여기 둔다.
 */
export type PartyTasteView = {
  userId: number;
  /** 대표 취향 비율(%) */
  tasteShare: number;
  /** 취향 태그 */
  tags: string[];
};

/** 5.9 공통 취향 결과 */
export type PartyMixView = {
  partyId: number;
  members: PartyTasteView[];
  /** 공통 취향 일치(%) */
  commonRate: number;
  /** 공통 취향 태그 */
  tags: string[];
};

/*
 * ─────────────────────────────────────────── MY 공개 프로필 (업무 -305)
 *
 * 7.2-A · 7.2-B · 7.2-C가 쓰는 값이다.
 *
 * **계약은 있다 — API-062 공개 프로필 조회 · API-063 취향 비교(`docs/api/social.md`).
 * 다만 둘 다 2차 MVP라 아직 없다.** 305는 계약을 찾기 전에 화면 기준으로 만들었고,
 * 그래서 아래 타입은 계약의 응답 모양과 다르다. 계약이 열릴 때 맞춘다.
 *
 * `[!]` 어긋난 곳
 * - API-062는 `user` · `recentFrames` · `popcorn.flavors` · `publicReviewCount` ·
 *   `isBlockedByMe`로 내려준다. 아래는 평평한 모양이다
 * - API-063은 `similarity`(0~1) · `sharedFlavors` · `flavorComparison`(맛별 내 것 /
 *   상대 것) · `commonMovies`를 준다. **장르 · 국가 · 시대 · 감독 4줄에 해당하는 값이
 *   계약에 없다.** 그 4줄은 와이어프레임 7.2-C가 그린 것이다
 * - 경로 파라미터가 `publicId`(`users.public_id`, ADR-0008)다. 내부 숫자 ID를 공개
 *   URL에 노출하지 않는다는 결정이라 라우트도 함께 바꿔야 한다
 *
 * 공개 범위 자체는 1차 계약에 있다(API-014 · API-015의 `visibility`, 7.8-B 공개 범위
 * 설정). 무엇이 공개되는지는 그 설정이 정하고, 이 타입은 **그 결과로 남은 것**만 담는다.
 */

/** 공개 프로필이 보여주는 수치. 팔로워 수 같은 개념은 Feelm에 없다 */
export type PublicProfileStats = {
  frameCount: number;
  popcornCount: number;
  /** 공개로 남긴 한줄평 수 */
  publicReviewCount: number;
};

/** 7.2-A · 7.2-B 공개 프로필 */
export type PublicProfileView = {
  userId: number;
  nickname: string;
  avatarUrl: string | null;
  /** 한 줄 소개. 레퍼런스 지시로 추가했다 */
  introduction: string;
  stats: PublicProfileStats;
  /** 공개된 취향 키워드 */
  keywords: TasteKeywordView[];
  /** 공개된 취향 필름. 최신순 */
  frames: {
    frameId: number;
    movieId: number;
    title: string;
    posterUrl: string | null;
  }[];
  /** 공개된 팝콘 맛 분포. 코드와 알 수만 담고 색·이름은 API-044에서 온다 */
  popcorn: { flavorCode: string; popcornCount: number }[];
  /**
   * 공개 한줄평. 7.2-A는 **항상 빈 배열**이다 — 본인 미리보기이고 와이어프레임이
   * `비공개로 설정한 항목(한줄평)은 표시되지 않아요`로 적고 있다
   */
  reviews: {
    ratingId: number;
    movieTitle: string;
    comment: string;
    score: number;
  }[];
  /**
   * 나와의 취향 일치도(%). 본인 프로필(7.2-A)에서는 `null`이다.
   * 7.2-C가 보여주는 값과 같은 수치다
   */
  matchRate: number | null;
};

/** 7.2-C 항목별 비교 한 줄 */
export type TasteCompareItem = {
  /** 장르 · 국가 · 시대 · 감독 */
  label: string;
  /** 일치율(%) */
  rate: number;
};

/**
 * 7.2-C 사용자 취향 비교.
 *
 * 산출 규칙이 계약에 없다. 와이어프레임의 값(전체 76% · 장르 82 · 국가 74 · 시대 71 ·
 * 감독 68)을 그대로 옮겼다.
 */
export type TasteCompareView = {
  me: { userId: number; nickname: string; avatarUrl: string | null };
  other: { userId: number; nickname: string; avatarUrl: string | null };
  /** 전체 일치율(%) */
  matchRate: number;
  /** 두 사람 모두 상위에 있는 맛의 코드 */
  sharedFlavorCodes: string[];
  items: TasteCompareItem[];
  /** 같이 볼 만한 영화. 레퍼런스 지시로 추가했다 */
  recommendations: {
    movieId: number;
    title: string;
    posterUrl: string | null;
  }[];
};

/**
 * 7.5 추천 기록 한 줄. API-048 항목 + 이 영화를 볼 수 있는 OTT.
 *
 * 와이어프레임은 날짜 옆에 `넷플릭스` 같은 OTT 이름을 적는다. API-048 `movie`에는
 * 제목 · 포스터만 있어 **어느 OTT인지 알 수 없다.** 제공 OTT가 여러 곳이면 무엇을
 * 적을지도 정해지지 않았다. 목업은 영화의 첫 제공처를 쓴다.
 */
export type RecommendationHistoryItemView = RecommendationHistoryItem & {
  ott: { code: string; name: string } | null;
};

export type RecommendationHistorySetView = Omit<RecommendationHistorySet, 'items'> & {
  items: RecommendationHistoryItemView[];
};

export type RecommendationHistoryView = Omit<RecommendationHistory, 'items'> & {
  items: RecommendationHistorySetView[];
};
