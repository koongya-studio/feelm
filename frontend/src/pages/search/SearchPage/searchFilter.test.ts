/*
 * 4.3 필터 상태 → API-028 질의 변환 (업무 S15P21E106-303)
 *
 * 와이어프레임은 개봉연도를 `전체` · `2020년대` · `2010년대` · `그 이전` 네 덩어리로
 * 고르게 하는데 계약은 `yearFrom` · `yearTo` 범위를 받는다. 그 사이를 바꾸는 코드라
 * 눈으로는 틀린 것이 보이지 않는다. **`그 이전`이 시작 없이 끝만 가진다**는 점이
 * 특히 그렇다.
 */
import { describe, expect, it } from 'vitest';
import { EMPTY_FILTER, isEmptyFilter, toggle, toSearchQuery } from './searchFilter';

/** API-029 `yearRange.min`. `그 이전`의 시작이 된다 */
const YEAR_MIN = 1920;

describe('개봉연도 덩어리 → 범위', () => {
  it('전체는 연도를 보내지 않는다', () => {
    const query = toSearchQuery('', { ...EMPTY_FILTER, year: 'ALL' }, YEAR_MIN);

    expect(query.yearFrom).toBeUndefined();
    expect(query.yearTo).toBeUndefined();
  });

  it('2020년대는 2020~2029다', () => {
    const query = toSearchQuery('', { ...EMPTY_FILTER, year: '2020S' }, YEAR_MIN);

    expect(query).toMatchObject({ yearFrom: 2020, yearTo: 2029 });
  });

  it('그 이전은 기준정보의 최솟값에서 시작한다', () => {
    const query = toSearchQuery('', { ...EMPTY_FILTER, year: 'BEFORE' }, YEAR_MIN);

    expect(query).toMatchObject({ yearFrom: YEAR_MIN, yearTo: 2009 });
  });
});

describe('빈 값 처리', () => {
  it('고르지 않은 필터는 질의에 넣지 않는다', () => {
    const query = toSearchQuery('  ', EMPTY_FILTER, YEAR_MIN);

    expect(query).toEqual({ sort: 'RELEVANCE' });
  });

  it('검색어는 앞뒤 공백을 떼고 넣는다', () => {
    const query = toSearchQuery('  나우 유  ', EMPTY_FILTER, YEAR_MIN);

    expect(query.q).toBe('나우 유');
  });
});

describe('isEmptyFilter', () => {
  it('정렬만 바꾼 것은 비어 있는 것으로 본다', () => {
    // 정렬은 조건이 아니라 순서다. 정렬만으로 검색을 시작하면 계약이 400을 준다
    expect(isEmptyFilter({ ...EMPTY_FILTER, sort: 'RATING' })).toBe(true);
  });

  it('하나라도 고르면 비어 있지 않다', () => {
    expect(isEmptyFilter({ ...EMPTY_FILTER, genre: ['DRAMA'] })).toBe(false);
    expect(isEmptyFilter({ ...EMPTY_FILTER, year: '2020S' })).toBe(false);
  });
});

describe('toggle', () => {
  it('없으면 넣고 있으면 뺀다', () => {
    expect(toggle(['A'], 'B')).toEqual(['A', 'B']);
    expect(toggle(['A', 'B'], 'A')).toEqual(['B']);
  });
});
