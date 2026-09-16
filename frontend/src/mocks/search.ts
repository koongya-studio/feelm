/*
 * 영화 검색 목업.
 *
 * **검색(API-028)과 필터 옵션(API-029)은 업무 S15P21E106-476에서 실제 호출로 바뀌었다.**
 * 이 목록이 남은 것은 4.1 급상승(API-064)이 아직 목업이기 때문이다 — 계약이 급상승
 * 응답에 OTT를 주지 않아 화면이 같은 movieId를 여기서 찾아 배지를 채운다
 * (`mocks/trending.ts` 머리말). 급상승이 실제 호출로 바뀌면 이 파일도 함께 사라진다.
 *
 * 제목은 와이어프레임 4.2가 적어 둔 문자열을 그대로 옮겼다. 포스터 이미지는 쓰지
 * 않으므로(자리표시자) 이미지 권리 문제는 생기지 않는다.
 *
 * `mocks/movies.ts`는 추천이 참조하는 기준 목업이고 movieId 101~로 시작한다. 겹치지
 * 않게 201~을 쓴다.
 */
import type { MovieSearchItem } from '../types/search';

/**
 * 급상승 행이 movieId로 찾아 쓰는 영화 목록.
 *
 * 쓰이는 것은 `ottProviders`뿐이지만, 항목 모양을 계약(API-028 `items[]`)에 맞춰 두면
 * 급상승 계약에 OTT가 추가됐을 때 무엇을 지워야 하는지가 분명해진다.
 */
export const mockSearchMovies: MovieSearchItem[] = [
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
  },
];
