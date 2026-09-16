/*
 * 메타 한 줄은 API-030의 빈 값을 그대로 만난다(업무 S15P21E106-475). 목업에는 늘 있던
 * 값이라 `null`이 들어오면 `undefined분` 같은 문구가 화면에 남는 자리다.
 */
import { describe, expect, it } from 'vitest';
import { movieMetaLine } from './movie';

describe('movieMetaLine', () => {
  it('개봉 연도 · 장르 · 상영 시간을 가운뎃점으로 잇는다', () => {
    expect(
      movieMetaLine({
        releaseDate: '2013-05-29',
        runtimeMinutes: 115,
        genres: [{ code: 'CAPER', name: '케이퍼' }],
      }),
    ).toBe('2013 · 케이퍼 · 115분');
  });

  it('없는 값은 자리를 차지하지 않는다', () => {
    expect(movieMetaLine({ releaseDate: null, runtimeMinutes: null, genres: [] })).toBe('');
    expect(
      movieMetaLine({
        releaseDate: null,
        runtimeMinutes: 90,
        genres: [{ code: 'DRAMA', name: '드라마' }],
      }),
    ).toBe('드라마 · 90분');
  });

  it('상영 시간 0분도 값이라 표시한다', () => {
    expect(movieMetaLine({ releaseDate: null, runtimeMinutes: 0, genres: [] })).toBe('0분');
  });
});
