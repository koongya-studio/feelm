/*
 * 영화 검색 접근 함수.
 * 대응 명세는 docs/api/movies-search.md이다.
 *
 * 업무 S15P21E106-476에서 검색(API-028)과 필터 옵션(API-029)을 실제 호출로 바꿨다.
 * 급상승(API-064)은 업무 -612에서 화면과 함께 지웠다 — 백엔드에 없는 계약을 목업으로
 * 보여 주지 않는다. 실제 구현이 생기면 그때 다시 붙인다.
 *
 * 그 빈자리는 `getTopRatedMovies`가 **검색 API로** 채운다(업무 -714). 급상승이 아니라
 * 평점 높은 영화다 — 아래 주석을 볼 것.
 *
 * 검색은 `api/movies.ts`에 두지 않고 나눴다. 그 파일은 영화 한 편(상세 · 유사 · 한줄평)을
 * 다루고, 여기는 목록을 만드는 질의를 다룬다.
 */
import { request } from './client';
import type { QueryEntry } from './client';
import type {
  FilterOptions,
  MovieSearchItem,
  MovieSearchQuery,
  MovieSearchResult,
} from '../types/search';

/**
 * 한 장에 받아 올 결과 수. 계약의 `size`이며 허용 범위는 1~50, 기본값은 20이다(API-028).
 *
 * 4.2는 한 줄이 포스터 한 장 높이라 20편이면 화면을 충분히 채운다. 나머지는 `hasNext`가
 * 참인 동안 `더 보기`로 이어 받는다.
 */
const PAGE_SIZE = 20;

/**
 * 개수만 셀 때 받아 오는 크기.
 *
 * `totalCount`는 cursor · size를 적용하기 전 값이라(API-028 처리 규칙) 한 장을 가장
 * 작게 받아도 개수는 같다. 4.3 시트가 조건을 바꿀 때마다 세므로 본문을 줄인다.
 */
const COUNT_PAGE_SIZE = 1;

/**
 * 평점 높은 영화를 고르는 기간(년).
 *
 * 연도 하한이 곧 검색 조건이다(검색어가 없으므로 이것이라도 있어야 계약이 받는다).
 * 2년으로 잡은 것은 오래된 명작만 올라와 검색 화면이 고정된 목록처럼 보이지 않게 하려는
 * 것이다. `[?]` 기간은 사용자 피드백을 보고 조정한다.
 */
const TOP_RATED_YEARS = 2;

/** 검색 조건을 계약의 Query Parameter로 편다. 배열은 같은 이름을 반복해 실린다 */
function toParams(query: MovieSearchQuery): Record<string, QueryEntry> {
  const { q, genre, country, yearFrom, yearTo, ott, sort } = query;

  return { q, genre, country, yearFrom, yearTo, ott, sort };
}

/**
 * 4.1 검색 전 화면에 까는 평점 높은 영화 (업무 S15P21E106-714)
 *
 * 검색하기 전에도 영화가 보이면 좋겠다는 사용자 피드백이다(2026-09-20). 그 자리는 원래
 * 급상승(API-064)이었는데 백엔드에 없어 업무 -612에서 걷어냈다. 기다리지 않고 지금 있는
 * 검색 API로 채운다.
 *
 * **검색어 없이 부른다.** 계약은 `q` 없이도 장르 · 국가 · OTT · 연도 중 하나만 있으면
 * 받는다(백엔드 `MovieSearchCriteria.isSearchConditionPresent`). 그래서 연도 하한만 걸고
 * `sort=RATING`으로 받는다.
 *
 * **급상승이라고 부르지 않는다.** 조회수 기반 급상승은 API-064의 몫이고 이것은 평점 높은
 * 영화다. 화면의 소제목도 `평점 높은 영화`다 — 근거 없는 이름을 붙이면 -612에서 지운
 * 문제가 이름만 바꿔 돌아온다.
 *
 * 실패하면 빈 배열이다. 곁들이는 목록이라 화면 전체를 실패 상태로 만들지 않는다.
 */
export async function getTopRatedMovies(): Promise<MovieSearchItem[]> {
  try {
    const { items } = await request<MovieSearchResult>('/movies/search', {
      query: {
        yearFrom: new Date().getFullYear() - TOP_RATED_YEARS + 1,
        sort: 'RATING',
        size: PAGE_SIZE,
      },
    });

    return items;
  } catch {
    return [];
  }
}

/**
 * API-028 영화 검색 · GET /api/v1/movies/search
 *
 * **회원 전용이다.** 로그인하지 않으면 `401`이며 `watched`도 요청한 사용자의 기록으로
 * 계산된다.
 *
 * 서로 다른 필터는 AND, 같은 필터의 복수 값은 OR로 묶인다(계약 처리 규칙). 화면은
 * `toSearchQuery`가 만든 조건을 그대로 넘기고, 결합은 서버가 한다.
 *
 * `cursor`는 **직전 응답의 `nextCursor`를 같은 조건으로 되돌려 줄 때만** 쓴다. 조건이
 * 바뀐 커서는 계약이 `400 VALIDATION_FAILED`로 거절하므로, 검색어나 필터가 바뀌면
 * 커서를 버리고 첫 장부터 받는다.
 */
export async function searchMovies(
  query: MovieSearchQuery,
  cursor?: string,
): Promise<MovieSearchResult> {
  return request<MovieSearchResult>('/movies/search', {
    query: { ...toParams(query), cursor, size: PAGE_SIZE },
  });
}

/**
 * 조건에 맞는 전체 건수만 센다. 4.3 시트의 `결과 보기 · N편`이 쓴다.
 *
 * 개수 전용 계약이 없어 검색을 그대로 부르되 한 장을 가장 작게 받는다. 목록을 그리지
 * 않으므로 `items`는 버린다.
 */
export async function countSearchResults(query: MovieSearchQuery): Promise<number> {
  const page = await request<MovieSearchResult>('/movies/search', {
    query: { ...toParams(query), size: COUNT_PAGE_SIZE },
  });

  return page.totalCount;
}

/**
 * API-029 검색 필터 옵션 조회 · GET /api/v1/movies/filter-options
 *
 * 공개 API다. 로그인 전에도 4.3 시트를 채울 수 있다. 실제 영화와 연결된 기준정보만
 * 오므로 데이터가 적으면 선택지도 적다.
 */
export async function getFilterOptions(): Promise<FilterOptions> {
  return request<FilterOptions>('/movies/filter-options', { auth: 'none' });
}
