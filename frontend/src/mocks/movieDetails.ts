/*
 * 영화 상세 목업. API-030 영화 상세 조회
 *
 * 기준 영화 데이터는 mocks/movies.ts에 있고 여기에는 상세 화면에만 필요한 필드
 * (시놉시스 · 국가 · 감독 · 출연 · OTT 링크 · 내 평가 · 감상 상태)를 movieId로 붙인다.
 *
 * 시놉시스와 출연 · 제작 이름은 와이어프레임 2.4에 적힌 문자열을 그대로 옮겼다.
 * 와이어프레임은 2.4에서만 실제 영화(`나우 유 씨 미`)의 제목 · 상영 시간 · 평균 별점을
 * 쓰고 2.1 · 2.2에서는 `영화 제목` 자리표시자를 쓴다. 같은 영화가 화면마다 다르게 보이면
 * 안 되므로 제목 · 연도 · 장르 · 상영 시간 · 평균 별점은 mocks/movies.ts의 값을 따르고,
 * 여기서는 와이어프레임 2.4에만 있는 값을 채웠다. 목업 정합성은 업무 -262에서 정리한다.
 */
import { mockMyRatings } from './frames';
import { findMockMovie } from './movies';
import type { MovieDetail } from '../types/movie';

/** 상세 화면에만 필요한 필드 */
type DetailExtras = Pick<
  MovieDetail,
  | 'synopsis'
  | 'countries'
  | 'directors'
  | 'cast'
  | 'ratingCount'
  | 'flavorCode'
  | 'ottProviders'
  | 'myRating'
  | 'myWatchStatus'
>;

const ottMaster: Record<string, { ottId: number; name: string }> = {
  NETFLIX: { ottId: 1, name: '넷플릭스' },
  TVING: { ottId: 2, name: '티빙' },
  WAVVE: { ottId: 3, name: '웨이브' },
  DISNEY_PLUS: { ottId: 4, name: '디즈니+' },
  COUPANG_PLAY: { ottId: 5, name: '쿠팡플레이' },
  APPLE_TV_PLUS: { ottId: 6, name: '애플 TV+' },
};

/*
 * `watchUrl`은 목업에 실제 주소를 두지 않는다. 화면은 주소가 없으면 외부로 이동하지 않고
 * OTT 링크 클릭만 기록한다(API-033). 실제 주소는 연동 단계에서 채워진다.
 */
function ottProviders(codes: string[], subscribed: string[]) {
  return codes.map((code) => ({
    ottId: ottMaster[code].ottId,
    code,
    name: ottMaster[code].name,
    watchUrl: null,
    subscribed: subscribed.includes(code),
  }));
}

const detailExtras: Record<number, DetailExtras> = {
  101: {
    synopsis:
      '천재 마술사 4인조 "포 호스맨"이 무대 위에서 파리의 은행을 터는 마술을 선보이고, 훔친 돈을 관객에게 뿌린다. FBI와 인터폴이 그들을 쫓지만, 모든 수사가 그들의 다음 트릭을 위한 판이었다는 사실이 드러나며 쫓는 자와 속는 자의 게임이 시작된다.',
    countries: [{ code: 'US', name: '미국' }],
    directors: [{ personId: 9001, name: '루이 리테리어' }],
    cast: [
      { personId: 9002, name: '제시 아이젠버그', character: 'J. Daniel Atlas' },
      { personId: 9003, name: '마크 러팔로', character: 'Dylan Rhodes' },
      { personId: 9004, name: '아일라 피셔', character: 'Henley Reeves' },
    ],
    ratingCount: 1280,
    flavorCode: 'FLAVOR_CAPER',
    // 구독 중인 OTT를 앞에 둔다(FR-16). 1.2 목업 선택과 무관하게 이 화면의 예시 값이다
    ottProviders: ottProviders(['NETFLIX', 'WAVVE'], ['NETFLIX']),
    myRating: null,
    myWatchStatus: null,
  },
  102: {
    synopsis: '한 도시의 연쇄 사건을 추적하는 형사와 기자가 서로 다른 진실에 도달한다.',
    countries: [{ code: 'KR', name: '한국' }],
    directors: [{ personId: 9011, name: '감독 B' }],
    cast: [{ personId: 9012, name: '배우 B1' }],
    ratingCount: 640,
    flavorCode: 'FLAVOR_THRILL',
    ottProviders: ottProviders(['WAVVE'], []),
    myRating: null,
    myWatchStatus: null,
  },
  103: {
    synopsis: '비 오는 항구 도시에서 서로를 속이는 세 사람의 하루를 담았다.',
    countries: [{ code: 'FR', name: '프랑스' }],
    directors: [{ personId: 9021, name: '감독 C' }],
    cast: [{ personId: 9022, name: '배우 C1' }],
    ratingCount: 410,
    flavorCode: 'FLAVOR_NOIR',
    ottProviders: ottProviders(['TVING', 'WAVVE'], ['TVING']),
    myRating: null,
    myWatchStatus: null,
  },

  /*
   * 3.1 취향 필름의 프레임 3개(401~403).
   *
   * 3.1과 3.1-A가 2.4로 이동하므로 상세도 있어야 흐름이 이어진다. 프레임은 평가와
   * 1:1이라 이미 평가한 영화이므로 `myRating`은 mocks/frames.ts의 `mockMyRatings`를
   * 그대로 쓰고 `myWatchStatus`는 `RATED`다. 101~103은 아직 평가하지 않은 추천 영화라
   * `myRating: null`을 그대로 둔다.
   *
   * 시놉시스 · 감독 · 출연 이름은 와이어프레임에 없어 102 · 103과 같은 자리표시자다.
   */
  401: {
    synopsis: '한 조직의 자금을 쫓는 형사와 내부 조력자가 서로를 이용하는 이야기.',
    countries: [{ code: 'KR', name: '한국' }],
    directors: [{ personId: 9031, name: '감독 D' }],
    cast: [{ personId: 9032, name: '배우 D1' }],
    ratingCount: 520,
    flavorCode: 'FLAVOR_CRIME',
    ottProviders: ottProviders(['NETFLIX'], ['NETFLIX']),
    myRating: mockMyRatings[401],
    myWatchStatus: 'RATED',
  },
  402: {
    synopsis: '무대 위 마술이 곧 알리바이가 되는 한탕 계획을 담았다.',
    countries: [{ code: 'US', name: '미국' }],
    directors: [{ personId: 9041, name: '감독 E' }],
    cast: [{ personId: 9042, name: '배우 E1' }],
    ratingCount: 980,
    flavorCode: 'FLAVOR_CAPER',
    ottProviders: ottProviders(['NETFLIX'], ['NETFLIX']),
    myRating: mockMyRatings[402],
    myWatchStatus: 'RATED',
  },
  403: {
    synopsis: '비 내리는 도시에서 옛 동료를 다시 만난 남자의 하룻밤.',
    countries: [{ code: 'FR', name: '프랑스' }],
    directors: [{ personId: 9051, name: '감독 F' }],
    cast: [{ personId: 9052, name: '배우 F1' }],
    ratingCount: 310,
    flavorCode: 'FLAVOR_NOIR',
    ottProviders: ottProviders(['TVING'], ['TVING']),
    myRating: mockMyRatings[403],
    myWatchStatus: 'RATED',
  },
};

export function findMockMovieDetail(movieId: number): MovieDetail | null {
  const extras = detailExtras[movieId];

  if (!extras) {
    return null;
  }

  const movie = findMockMovie(movieId);

  return {
    movieId: movie.movieId,
    title: movie.title,
    releaseDate: `${movie.releaseYear}-01-01`,
    runtimeMinutes: movie.runtimeMinutes,
    genres: movie.genres.map((name) => ({ code: name, name })),
    posterUrl: movie.posterUrl,
    backdropUrl: null,
    averageRating: movie.averageRating,
    recommendation: null,
    ...extras,
  };
}
