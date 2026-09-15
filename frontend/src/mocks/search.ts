/*
 * 영화 검색 목업. API-028 영화 검색 · API-029 검색 필터 옵션 조회
 *
 * 검색 결과 목록만은 와이어프레임 4.2가 실제 제목(`나우 유 씨 미` 등)을 적어 두었다.
 * 다른 화면처럼 `영화 제목`으로 두면 **한 검색어가 여러 편을 걸러 내는 모습**을 보일 수
 * 없어 검색 화면의 확인이 성립하지 않는다. 그래서 4.2가 적어 둔 문자열만 그대로 옮겼다.
 * 포스터 이미지는 쓰지 않으므로(자리표시자) 이미지 권리 문제는 생기지 않는다.
 *
 * `mocks/movies.ts`는 추천이 참조하는 기준 목업이고 movieId 101~로 시작한다. 검색은
 * 그 목록으로는 검색어 대조를 보일 수 없으므로 별도로 둔다. 겹치지 않게 201~을 쓴다.
 */
import { mockOtts } from './otts';
import type { FilterOptions, MovieSearchItem } from '../types/search';

/**
 * 검색 대상 목록.
 *
 * 4.2가 `나우 유`로 4건을 찾는 화면이므로 그 4건을 그대로 두고, 필터가 실제로 걸러 내는
 * 모습을 보이도록 장르 · 국가 · 연도 · OTT가 서로 다른 몇 편을 더 두었다.
 */
export const mockSearchMovies: (MovieSearchItem & {
  /** API-028 질의에는 쓰이지만 응답에 없는 값. 목업이 필터를 흉내 내기 위해 둔다 */
  countryCode: string;
  genreCodes: string[];
  /** 검색어 대조에 쓰는 배우 · 감독 이름 (API-028 `q`는 이 셋을 본다) */
  people: string[];
})[] = [
  {
    movieId: 201,
    title: '나우 유 씨 미',
    originalTitle: 'Now You See Me',
    posterUrl: null,
    releaseYear: 2013,
    genres: ['케이퍼'],
    averageRating: 7.3,
    ottProviders: ['NETFLIX', 'WAVVE'],
    watched: false,
    countryCode: 'US',
    genreCodes: ['CAPER'],
    people: ['루이스 리터리어'],
  },
  {
    movieId: 202,
    title: '나우 유 씨 미 2',
    originalTitle: 'Now You See Me 2',
    posterUrl: null,
    releaseYear: 2016,
    genres: ['케이퍼'],
    averageRating: 6.9,
    ottProviders: ['TVING'],
    watched: false,
    countryCode: 'US',
    genreCodes: ['CAPER'],
    people: ['존 추'],
  },
  {
    movieId: 203,
    title: '나우 유 씨 미 3',
    originalTitle: 'Now You See Me 3',
    posterUrl: null,
    releaseYear: 2025,
    genres: ['케이퍼'],
    averageRating: 7.1,
    ottProviders: ['NETFLIX', 'TVING'],
    watched: false,
    countryCode: 'US',
    genreCodes: ['CAPER'],
    people: ['루벤 플레이셔'],
  },
  {
    // 4.2에서 유일하게 `시청 가능한 OTT 없음`이 붙는 행이다
    movieId: 204,
    title: '나우 유 히어 미',
    originalTitle: 'Now You Hear Me',
    posterUrl: null,
    releaseYear: 2019,
    genres: ['드라마'],
    averageRating: 6.4,
    ottProviders: [],
    watched: false,
    countryCode: 'KR',
    genreCodes: ['DRAMA'],
    people: ['김감독'],
  },
  {
    // 4.1 급상승 1위와 같은 영화다. 급상승에서 눌러 들어가는 경로를 확인할 수 있다
    movieId: 205,
    title: '웡카와 초콜릿',
    originalTitle: 'Wonka and Chocolate',
    posterUrl: null,
    releaseYear: 2023,
    genres: ['판타지'],
    averageRating: 4.3,
    ottProviders: ['NETFLIX'],
    watched: false,
    countryCode: 'US',
    genreCodes: ['FANTASY'],
    people: ['폴 킹'],
  },
  {
    movieId: 206,
    title: '달의 뒷면',
    originalTitle: 'The Far Side of the Moon',
    posterUrl: null,
    releaseYear: 2021,
    genres: ['SF'],
    averageRating: 4.1,
    ottProviders: ['TVING'],
    watched: false,
    countryCode: 'KR',
    genreCodes: ['SF'],
    people: ['크리스토퍼 놀란'],
  },
  {
    movieId: 207,
    title: '한밤의 추격자',
    originalTitle: 'Midnight Chaser',
    posterUrl: null,
    releaseYear: 2018,
    genres: ['스릴러', '액션'],
    averageRating: 4.4,
    ottProviders: ['NETFLIX'],
    watched: true,
    countryCode: 'KR',
    genreCodes: ['THRILLER', 'ACTION'],
    people: ['나감독'],
  },
  {
    movieId: 208,
    title: '여름의 끝',
    originalTitle: 'End of Summer',
    posterUrl: null,
    releaseYear: 2009,
    genres: ['드라마'],
    averageRating: 4.0,
    ottProviders: ['NETFLIX'],
    watched: false,
    countryCode: 'JP',
    genreCodes: ['DRAMA'],
    people: ['고레에다'],
  },
  {
    movieId: 209,
    title: '그림자 도시',
    originalTitle: 'Shadow City',
    posterUrl: null,
    releaseYear: 2015,
    genres: ['느와르'],
    averageRating: 3.9,
    ottProviders: ['TVING'],
    watched: false,
    countryCode: 'US',
    genreCodes: ['THRILLER'],
    people: ['정감독'],
  },
  {
    movieId: 210,
    title: '마지막 상영회',
    originalTitle: 'The Last Screening',
    posterUrl: null,
    releaseYear: 2022,
    genres: ['드라마'],
    averageRating: 4.2,
    ottProviders: ['NETFLIX'],
    watched: false,
    countryCode: 'KR',
    genreCodes: ['DRAMA'],
    people: ['이감독'],
  },
];

/**
 * API-029 필터 옵션.
 *
 * 와이어프레임 4.3이 보여주는 선택지를 그대로 옮겼다. 계약이 `code`를 정하지 않았으므로
 * `[?]` 코드값은 임시다. 실제 기준정보가 나오면 이 파일을 교체한다.
 */
export const mockFilterOptions: FilterOptions = {
  genres: [
    { code: 'THRILLER', name: '스릴러' },
    { code: 'ACTION', name: '액션' },
    { code: 'DRAMA', name: '드라마' },
    { code: 'COMEDY', name: '코미디' },
    { code: 'SF', name: 'SF' },
    { code: 'HORROR', name: '공포' },
  ],
  countries: [
    { code: 'KR', name: '한국' },
    { code: 'US', name: '미국' },
    { code: 'JP', name: '일본' },
    { code: 'EU', name: '유럽' },
    { code: 'ETC', name: '기타' },
  ],
  yearRange: { min: 1920, max: 2026 },
  // 지원 5종과 식별자는 `mocks/otts.ts`가 기준이다(업무 -379)
  otts: mockOtts.map(({ ottId, code, name }) => ({ ottId, code, name })),
};
