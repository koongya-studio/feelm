/*
 * API-028 영화 검색 목업의 필터 결합 규칙 (업무 S15P21E106-303)
 *
 * 계약이 정한 규칙은 **서로 다른 필터는 AND, 같은 필터의 복수 값은 OR**이다. 목업이
 * 이 규칙을 흉내 내지 않으면 4.3 필터 화면이 거짓말을 하게 되고, 실제 연동에서 결과가
 * 달라진다. 규칙 자체를 고정한다.
 *
 * 400 두 가지(검색어·필터가 모두 없음, 연도 범위 역전)도 함께 본다. 화면이 그 조합을
 * 보내지 않도록 막고 있어 눈으로는 확인되지 않는 경로다.
 */
import { describe, expect, it } from 'vitest';
import { searchMovies } from './search';
import { ApiError } from './mockTransport';

describe('API-028 검색어', () => {
  it('제목으로 찾는다', async () => {
    const result = await searchMovies({ q: '나우 유' });

    expect(result.items.map((movie) => movie.movieId)).toEqual([201, 202, 203, 204]);
  });

  it('감독 이름으로도 찾는다', async () => {
    const result = await searchMovies({ q: '크리스토퍼 놀란' });

    expect(result.items.map((movie) => movie.title)).toEqual(['달의 뒷면']);
  });

  it('맞는 것이 없으면 빈 목록과 함께 성공한다', async () => {
    const result = await searchMovies({ q: '인터스텔라22' });

    expect(result.items).toEqual([]);
  });
});

describe('API-028 필터 결합', () => {
  it('같은 필터의 복수 값은 OR로 묶인다', async () => {
    const drama = await searchMovies({ genre: ['DRAMA'] });
    const sf = await searchMovies({ genre: ['SF'] });
    const both = await searchMovies({ genre: ['DRAMA', 'SF'] });

    expect(both.items.length).toBe(drama.items.length + sf.items.length);
  });

  it('서로 다른 필터는 AND로 묶인다', async () => {
    const drama = await searchMovies({ genre: ['DRAMA'] });
    const korean = await searchMovies({ genre: ['DRAMA'], country: ['KR'] });

    expect(korean.items.length).toBeLessThan(drama.items.length);
    expect(korean.items.every((movie) => movie.genres.includes('드라마'))).toBe(true);
  });

  it('검색어와 필터도 AND로 묶인다', async () => {
    const result = await searchMovies({ q: '나우 유', genre: ['DRAMA'] });

    expect(result.items.map((movie) => movie.title)).toEqual(['나우 유 히어 미']);
  });

  it('OTT는 ottId로 받아 코드로 대조한다', async () => {
    const result = await searchMovies({ ott: [2] });

    expect(result.items.every((movie) => movie.ottProviders.includes('TVING'))).toBe(true);
  });

  it('개봉연도는 범위로 거른다', async () => {
    const result = await searchMovies({ yearFrom: 2020, yearTo: 2029 });

    expect(result.items.every((movie) => movie.releaseYear >= 2020)).toBe(true);
    expect(result.items.every((movie) => movie.releaseYear <= 2029)).toBe(true);
  });
});

describe('API-028 정렬', () => {
  it('평점순은 높은 순이다', async () => {
    const { items } = await searchMovies({ q: '나우 유', sort: 'RATING' });
    const scores = items.map((movie) => movie.averageRating);

    expect([...scores].sort((a, b) => b - a)).toEqual(scores);
  });

  it('최신순은 개봉연도 내림차순이다', async () => {
    const { items } = await searchMovies({ q: '나우 유', sort: 'RELEASE_DATE' });

    expect(items.map((movie) => movie.releaseYear)).toEqual([2025, 2019, 2016, 2013]);
  });
});

describe('API-028 오류', () => {
  it('검색어와 필터가 모두 없으면 400이다', async () => {
    await expect(searchMovies({})).rejects.toBeInstanceOf(ApiError);
  });

  it('연도 범위가 뒤집히면 400이다', async () => {
    await expect(searchMovies({ yearFrom: 2020, yearTo: 2010 })).rejects.toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
    });
  });
});

describe('응답 모양', () => {
  it('목업 전용 필드를 응답에 섞지 않는다', async () => {
    const [first] = (await searchMovies({ q: '나우 유' })).items;

    // 계약(API-028 `items[]`)에 없는 필드가 새어 나가면 실제 연동에서 사라진다
    expect(first).not.toHaveProperty('countryCode');
    expect(first).not.toHaveProperty('genreCodes');
    expect(first).not.toHaveProperty('people');
  });
});
