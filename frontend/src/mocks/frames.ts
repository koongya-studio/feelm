/*
 * 필름(프레임) 목업. API-042 내 필름(프레임 목록) 조회 · API-043 프레임 상세 조회
 *
 * 프레임은 평가와 1:1이다(API-042 처리 규칙). 영화 기준값은 mocks/movies.ts에서
 * 가져오고 여기에는 프레임에만 있는 값(별점 · 맛 · 생성 시각 · 감상 기록)을 둔다.
 *
 * 표시값은 와이어프레임 3.1 · 3.1-A에 적힌 문자열을 그대로 옮겼다.
 * 3.1 최근 추가된 영화 별점 ★4.5 · ★4.0 · ★5.0
 * 3.1-A 내 별점 4.0 · 감상 날짜 2026. 8. 24 · 넷플릭스 · 공개 한줄평
 *
 * 3.1-A는 별점 4.0인 프레임(502)에 대응한다.
 *
 * `totalCount`는 와이어프레임 3.1의 `필름 100프레임`을 따른다. 목록 항목은 3개만
 * 두고 `hasNext: true`로 남겨 두었다. 스트립이 몇 개까지 보여주는지와 페이지네이션은
 * 미확정이다(screen-spec.md 3.1 미확정).
 */
import { findMockMovie } from './movies';
import type { MyRating } from '../types/movie';
import type { FrameList, FrameSummary } from '../types/taste';
import type { FrameDetailView } from '../types/unspecified';

/**
 * 프레임과 1:1인 내 평가(API-042 처리 규칙). movieId로 찾는다.
 *
 * mocks/movieDetails.ts의 `myRating`도 이 값을 쓴다. 프레임이 있는 영화는 이미 평가한
 * 영화이므로 2.4 영화 상세에도 같은 별점 · 한줄평이 보여야 한다.
 *
 * 402의 한줄평은 와이어프레임 3.1-A 문구 그대로다. 와이어프레임은 같은 문장을 2.4의
 * 다른 사용자 한줄평(`film_daily`)에도 쓴다. 둘 다 와이어프레임 문구라 그대로 두었다.
 */
export const mockMyRatings: Record<number, MyRating> = {
  401: { ratingId: 901, score: 4.5, comment: null, visibility: 'PRIVATE' },
  402: {
    ratingId: 902,
    score: 4.0,
    comment: '마술 트릭 하나하나가 복선이라 두 번 보게 돼요.',
    visibility: 'PUBLIC',
  },
  403: { ratingId: 903, score: 5.0, comment: null, visibility: 'PRIVATE' },
};

function summary(
  frameId: number,
  movieId: number,
  flavorCode: string,
  createdAt: string,
): FrameSummary {
  const movie = findMockMovie(movieId);

  return {
    frameId,
    movie: { movieId: movie.movieId, title: movie.title, posterUrl: movie.posterUrl },
    score: mockMyRatings[movieId].score,
    flavorCode,
    createdAt,
  };
}

/*
 * 시각은 정오 기준으로 두었다. 자정 근처로 두면 보는 사람의 시간대에 따라 화면에 찍히는
 * 날짜가 하루씩 달라져 검증이 흔들린다.
 */
export const mockFrameList: FrameList = {
  totalCount: 100,
  items: [
    summary(501, 401, 'FLAVOR_CRIME', '2026-09-06T12:30:00Z'),
    summary(502, 402, 'FLAVOR_CAPER', '2026-08-24T12:00:00Z'),
    summary(503, 403, 'FLAVOR_NOIR', '2026-08-18T12:00:00Z'),
  ],
  nextCursor: 'mock-cursor-2',
  hasNext: true,
};

/** 프레임에만 있는 값. 영화 기준값은 mocks/movies.ts, 평가는 mockMyRatings에서 붙인다 */
type FrameExtras = Omit<FrameDetailView, 'movie' | 'rating'> & { movieId: number };

const frameExtras: Record<number, FrameExtras> = {
  501: {
    frameId: 501,
    movieId: 401,
    flavorCode: 'FLAVOR_CRIME',
    watchRecord: {
      ott: { code: 'NETFLIX', name: '넷플릭스' },
      clickedAt: '2026-09-05T12:00:00Z',
      watchedAt: '2026-09-06T12:00:00Z',
      ratedAt: '2026-09-06T12:30:00Z',
    },
  },
  // 와이어프레임 3.1-A가 보여주는 프레임
  502: {
    frameId: 502,
    movieId: 402,
    flavorCode: 'FLAVOR_CAPER',
    watchRecord: {
      ott: { code: 'NETFLIX', name: '넷플릭스' },
      clickedAt: '2026-08-23T12:00:00Z',
      watchedAt: '2026-08-24T12:00:00Z',
      ratedAt: '2026-08-24T13:20:00Z',
    },
  },
  /*
   * 감상 기록 없이 평가한 프레임. API-043 처리 규칙대로 `ott` · `clickedAt`이 null이다.
   * 한줄평도 없어 3.1-A에서 두 줄이 빠지는 모습을 확인할 수 있다
   * (screen-spec.md 3.1-A 미확정 항목).
   */
  503: {
    frameId: 503,
    movieId: 403,
    flavorCode: 'FLAVOR_NOIR',
    watchRecord: {
      ott: null,
      clickedAt: null,
      watchedAt: null,
      ratedAt: '2026-08-18T12:00:00Z',
    },
  },
};

export function findMockFrameDetail(frameId: number): FrameDetailView | null {
  const extras = frameExtras[frameId];

  if (!extras) {
    return null;
  }

  const { movieId, ...rest } = extras;
  const movie = findMockMovie(movieId);

  return {
    ...rest,
    rating: mockMyRatings[movieId],
    movie: {
      movieId: movie.movieId,
      title: movie.title,
      posterUrl: movie.posterUrl,
      releaseYear: movie.releaseYear,
      genres: movie.genres,
      // API-043은 감독 이름 문자열 배열이다. 3.1-A는 표시하지 않는다
      directors: [],
      runtimeMinutes: movie.runtimeMinutes,
    },
  };
}
