import { describe, expect, it } from 'vitest';
import { ottMovieUrl } from './ott';

describe('ottMovieUrl', () => {
  it.each([
    [
      'NETFLIX',
      'https://www.netflix.com/search?q=%ED%8C%8C%EC%9D%B4%ED%8A%B8%20%ED%81%B4%EB%9F%BD',
    ],
    [
      'WATCHA',
      'https://watcha.com/ko/search?query=%ED%8C%8C%EC%9D%B4%ED%8A%B8%20%ED%81%B4%EB%9F%BD',
    ],
    [
      'WAVVE',
      'https://www.wavve.com/search?searchWord=%ED%8C%8C%EC%9D%B4%ED%8A%B8%20%ED%81%B4%EB%9F%BD',
    ],
    [
      'TVING',
      'https://www.tving.com/search?keyword=%ED%8C%8C%EC%9D%B4%ED%8A%B8%20%ED%81%B4%EB%9F%BD',
    ],
  ])('%s는 영화 제목을 검색어로 전달한다', (code, expected) => {
    expect(ottMovieUrl(code, '파이트 클럽')).toBe(expected);
  });

  it('Disney+는 검색어 URL 대신 한국 메인 페이지로 보낸다', () => {
    expect(ottMovieUrl('DISNEY_PLUS', '파이트 클럽')).toBe('https://www.disneyplus.com/ko-kr');
  });

  it('지원하지 않는 OTT는 임의의 주소를 만들지 않는다', () => {
    expect(ottMovieUrl('UNKNOWN', '파이트 클럽')).toBeNull();
  });
});
