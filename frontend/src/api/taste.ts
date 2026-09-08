/*
 * 취향 분석 데이터 접근 함수.
 * 대응 명세는 docs/api/taste.md이다.
 *
 * 프레임 목록(API-042) · 프레임 상세(API-043) · 팝콘 버킷(API-041)은 기준 목업에
 * 이번 세션의 평가를 더해 응답을 만든다. 평가 하나가 프레임 1개와 팝콘 1알을 만들기
 * 때문이다(FR-07, FR-09). 그래서 2.5에서 평가하면 3.1과 3.2가 함께 올라간다.
 */
import { findMockFrameDetail, mockFrameList } from '../mocks/frames';
import { findMockMovie } from '../mocks/movies';
import { mockPopcornBucket } from '../mocks/popcornBucket';
import { mockPopcornFlavors } from '../mocks/popcornFlavors';
import { findRatingByFrame, findRatingByMovie, getRatings } from '../mocks/sessionStore';
import { mockTasteAnalysis, mockTasteFlow } from '../mocks/taste';
import { respond, respondError } from './mockTransport';
import type {
  FrameList,
  FrameSummary,
  PopcornBucket,
  PopcornBucketFlavor,
  PopcornFlavor,
  TasteAnalysis,
} from '../types/taste';
import type { Rating } from '../types/watch';
import type { FrameDetailView, TasteFlowView } from '../types/unspecified';

/** 이번 세션의 평가를 프레임 목록 항목으로 바꾼다. 최신순이라 앞에 붙는다 */
function sessionFrames(): FrameSummary[] {
  return [...getRatings()].reverse().map((rating) => {
    const movie = findMockMovie(rating.movieId);

    return {
      frameId: rating.frame.frameId,
      movie: { movieId: movie.movieId, title: movie.title, posterUrl: movie.posterUrl },
      score: rating.score,
      flavorCode: rating.popcorn.flavorCode,
      createdAt: rating.frame.createdAt,
    };
  });
}

/** API-040 취향 분석·키워드 조회 · GET /api/v1/users/me/taste */
export async function getMyTaste(): Promise<TasteAnalysis> {
  return respond(mockTasteAnalysis);
}

/**
 * API-044 팝콘 맛 기준정보 조회 · GET /api/v1/popcorn-flavors
 *
 * 화면은 `code`로 매칭하고 표시명 · 색상을 하드코딩하지 않는다. 8종의 표시명과 색상은
 * API-044 미정 사항이므로 목업 값은 잠정값이다.
 */
export async function getPopcornFlavors(): Promise<PopcornFlavor[]> {
  return respond(mockPopcornFlavors);
}

/**
 * API-041 팝콘 버킷 조회 · GET /api/v1/users/me/popcorn
 *
 * 기준 집계에 이번 세션의 평가를 맛별로 더한다. 평균 평점도 함께 다시 계산한다.
 */
export async function getPopcornBucket(): Promise<PopcornBucket> {
  const ratings = getRatings();

  const flavors: PopcornBucketFlavor[] = mockPopcornBucket.flavors.map((flavor) => {
    const added = ratings.filter((rating) => rating.popcorn.flavorCode === flavor.flavorCode);

    if (added.length === 0) {
      return flavor;
    }

    const addedTotal = added.reduce((sum, rating) => sum + rating.score, 0);
    const baseTotal = (flavor.averageScore ?? 0) * flavor.popcornCount;
    const popcornCount = flavor.popcornCount + added.length;

    return {
      ...flavor,
      popcornCount,
      averageScore: Math.round(((baseTotal + addedTotal) / popcornCount) * 10) / 10,
      isNewFlavor: popcornCount < mockPopcornBucket.newFlavorThreshold,
    };
  });

  return respond({
    ...mockPopcornBucket,
    totalCount: flavors.reduce((sum, flavor) => sum + flavor.popcornCount, 0),
    flavors,
  });
}

/**
 * API-042 내 필름(프레임 목록) 조회 · GET /api/v1/users/me/frames
 *
 * 계약의 `flavorCode` · `cursor` · `size` 질의 인자는 쓰는 화면이 생기면 추가한다.
 * 3.1은 첫 페이지만 쓴다.
 */
export async function getMyFrames(): Promise<FrameList> {
  const added = sessionFrames();

  return respond({
    ...mockFrameList,
    totalCount: mockFrameList.totalCount + added.length,
    items: [...added, ...mockFrameList.items],
  });
}

/** 3.1과 2.6 · 2.7이 함께 쓰는 현재 프레임 수 */
export async function getMyFrameCount(): Promise<number> {
  return mockFrameList.totalCount + getRatings().length;
}

/** 2.6 · 2.7이 쓰는 현재 팝콘 알 수 */
export async function getMyPopcornCount(): Promise<number> {
  return mockPopcornBucket.totalCount + getRatings().length;
}

/** 이번 세션에 만들어진 프레임의 상세. 감상 기록은 평가와 같은 시각으로 채운다 */
function sessionFrameDetail(rating: Rating): FrameDetailView {
  const movie = findMockMovie(rating.movieId);

  return {
    frameId: rating.frame.frameId,
    flavorCode: rating.popcorn.flavorCode,
    movie: {
      movieId: movie.movieId,
      title: movie.title,
      posterUrl: movie.posterUrl,
      releaseYear: movie.releaseYear,
      genres: movie.genres,
      directors: [],
      runtimeMinutes: movie.runtimeMinutes,
    },
    rating: {
      ratingId: rating.ratingId,
      score: rating.score,
      comment: rating.comment,
      visibility: rating.visibility,
    },
    /*
     * 감상 기록은 API-036 응답에 없다. 이 단계에서는 평가 시각만 채우고 OTT와
     * 링크 클릭 시각은 `null`로 둔다. API-043 처리 규칙이 허용하는 형태다.
     */
    watchRecord: {
      ott: null,
      clickedAt: null,
      watchedAt: null,
      ratedAt: rating.createdAt,
    },
  };
}

/** API-043 프레임 상세 조회 · GET /api/v1/frames/{frameId} */
export async function getFrameDetail(frameId: number): Promise<FrameDetailView> {
  const sessionRating = findRatingByFrame(frameId);

  if (sessionRating) {
    return respond(sessionFrameDetail(sessionRating));
  }

  const frame = findMockFrameDetail(frameId);

  if (!frame) {
    return respondError(404, 'RESOURCE_NOT_FOUND', '프레임을 찾을 수 없습니다.');
  }

  // 기준 프레임의 영화를 이번 세션에 다시 평가했다면 그 평가를 보여준다
  const updated = findRatingByMovie(frame.movie.movieId);

  if (!updated) {
    return respond(frame);
  }

  return respond({
    ...frame,
    rating: {
      ratingId: updated.ratingId,
      score: updated.score,
      comment: updated.comment,
      visibility: updated.visibility,
    },
  });
}

/**
 * 3.1 `요즘 취향 흐름` 카드. **계약에 대응 API가 없다(CR-16).**
 *
 * 흐름 문장과 키워드 근거 문장을 내려주는 필드가 없어 화면이 목업을 직접 import하지
 * 않도록 함수만 따로 둔다. 계약이 정해지면 API-040(`getMyTaste`)으로 합친다.
 */
export async function getTasteFlow(): Promise<TasteFlowView> {
  return respond(mockTasteFlow);
}
