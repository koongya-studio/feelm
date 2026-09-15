/*
 * 4.3 검색 필터의 화면 상태와 계약 질의 사이 변환.
 *
 * 화면 파일에 두지 않는 것은 화면 컴포넌트만 내보내야 하기 때문이다
 * (`react-refresh/only-export-components`).
 */
import type { MovieSearchQuery, MovieSearchSort } from '../../../types/search';

/**
 * 개봉연도 선택지.
 *
 * 계약은 `yearFrom` · `yearTo` 범위를 받는데(API-028) 와이어프레임 4.3은 `전체` ·
 * `2020년대` · `2010년대` · `그 이전` 네 덩어리로 고르게 한다. 화면이 고른 덩어리를
 * 범위로 바꾼다.
 *
 * `그 이전`의 시작은 API-029 `yearRange.min`이므로 여기서 정하지 않고 비워 둔다.
 */
export const YEAR_BUCKETS = [
  { id: 'ALL', label: '전체', from: undefined, to: undefined },
  { id: '2020S', label: '2020년대', from: 2020, to: 2029 },
  { id: '2010S', label: '2010년대', from: 2010, to: 2019 },
  { id: 'BEFORE', label: '그 이전', from: undefined, to: 2009 },
] as const;

export type YearBucketId = (typeof YEAR_BUCKETS)[number]['id'];

/**
 * 정렬 선택지.
 *
 * `[!]` 와이어프레임은 `취향 일치순` · `최신순` · `평점순`인데 계약(API-028 `sort`)은
 * `RELEVANCE` · `RATING` · `RELEASE_DATE`다. **`취향 일치순`에 해당하는 값이 계약에
 * 없다.** `RELEVANCE`는 검색어 적합도이지 취향 일치도가 아니다. 계약을 기준으로 하되
 * (`screen-spec.md` 2장 계약 우선 원칙) 화면 문구는 계약이 실제로 하는 일에 맞춰
 * `관련도순`으로 바꿨다. 취향 일치 정렬이 필요하면 계약에 값을 추가해야 한다.
 */
export const SORT_OPTIONS: { value: MovieSearchSort; label: string }[] = [
  { value: 'RELEVANCE', label: '관련도순' },
  { value: 'RELEASE_DATE', label: '최신순' },
  { value: 'RATING', label: '평점순' },
];

/** 화면이 들고 다니는 필터 상태 */
export type SearchFilterState = {
  genre: string[];
  country: string[];
  ott: number[];
  year: YearBucketId;
  sort: MovieSearchSort;
};

export const EMPTY_FILTER: SearchFilterState = {
  genre: [],
  country: [],
  ott: [],
  year: 'ALL',
  sort: 'RELEVANCE',
};

/** 아무것도 고르지 않은 상태인지. 검색어 없이 조회할 수 있는지를 가른다 */
export function isEmptyFilter(filter: SearchFilterState): boolean {
  return (
    filter.genre.length === 0 &&
    filter.country.length === 0 &&
    filter.ott.length === 0 &&
    filter.year === 'ALL'
  );
}

/** 값이 있으면 빼고 없으면 넣는다. 같은 필터의 복수 값은 OR로 묶인다(API-028) */
export function toggle<T>(values: T[], value: T): T[] {
  return values.includes(value) ? values.filter((item) => item !== value) : [...values, value];
}

/** 화면 상태를 API-028 질의로 바꾼다. 빈 값은 넣지 않는다 */
export function toSearchQuery(
  keyword: string,
  filter: SearchFilterState,
  yearMin: number,
): MovieSearchQuery {
  const bucket = YEAR_BUCKETS.find((item) => item.id === filter.year) ?? YEAR_BUCKETS[0];
  const query: MovieSearchQuery = { sort: filter.sort };

  if (keyword.trim()) query.q = keyword.trim();
  if (filter.genre.length) query.genre = filter.genre;
  if (filter.country.length) query.country = filter.country;
  if (filter.ott.length) query.ott = filter.ott;

  if (bucket.id !== 'ALL') {
    // `그 이전`은 시작이 없으므로 기준정보의 최솟값을 쓴다
    query.yearFrom = bucket.from ?? yearMin;
    query.yearTo = bucket.to;
  }

  return query;
}
