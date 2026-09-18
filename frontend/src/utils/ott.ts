/*
 * OTT 표시 규칙.
 *
 * 와이어프레임은 OTT를 로고 대신 한 글자로 표시한다(common-components.md 4.4).
 * 계약에는 이 글자가 없으므로 코드에서 만든다.
 *
 * 지원 OTT가 다섯 개로 확정되면서 **첫 글자가 겹치는 OTT가 실제로 들어왔다**
 * (WATCHA · WAVVE). 기준정보에 표시 글자가 없는 것은 그대로라 배지는 API-025가 주는
 * 로고를 쓰고(`components/OttBadge`), 이 함수는 로고로 그리지 못하는 자리의 대체 표시다.
 * 겹치는 왓챠만 한글 첫 글자로 구분한다 — 와이어프레임의 N · D · W · T는 그대로 두고
 * 충돌한 하나만 바꾸는 쪽이 기존 표기를 덜 흔든다.
 *
 * **로고를 쓰게 된 뒤에도 이 예외는 남는다**(업무 S15P21E106-441에서 판단). 글자로
 * 떨어지는 자리가 없어지지 않기 때문이다 — 기준정보를 아직 받지 못한 첫 순간, 조회가
 * 실패한 동안, 로고 이미지를 불러오지 못했을 때다. 그 상태에서 왓챠와 웨이브가 같은
 * 글자가 되면 사용자는 두 OTT를 구분할 수 없다.
 */
const BADGE_LETTERS: Record<string, string> = {
  NETFLIX: 'N',
  DISNEY_PLUS: 'D',
  WAVVE: 'W',
  TVING: 'T',
  // WAVVE와 첫 글자가 같다. 로고를 못 쓰는 자리에서 둘이 구분되지 않는다
  WATCHA: '왓',
};

export function ottBadgeLetter(code: string): string {
  return BADGE_LETTERS[code] ?? code.charAt(0);
}

/**
 * 영화 상세의 OTT 버튼 목적지.
 *
 * 현재 API-030에 적재된 `watchUrl`은 JustWatch 링크라 OTT 자체의 검색 결과로 이어지지
 * 않는다. 상세 콘텐츠 URL은 각 OTT의 공개 API 없이는 안정적으로 만들 수 없으므로,
 * 영화 제목을 공식 검색 페이지에 전달한다. Disney+는 웹 검색어 URL을 지원하지 않아
 * 한국 메인 페이지로 보낸다.
 */
export function ottMovieUrl(code: string, movieTitle: string): string | null {
  const query = encodeURIComponent(movieTitle);

  switch (code) {
    case 'NETFLIX':
      return `https://www.netflix.com/search?q=${query}`;
    case 'WATCHA':
      return `https://watcha.com/ko/search?query=${query}`;
    case 'WAVVE':
      return `https://www.wavve.com/search?searchWord=${query}`;
    case 'TVING':
      return `https://www.tving.com/search?keyword=${query}`;
    case 'DISNEY_PLUS':
      return 'https://www.disneyplus.com/ko-kr';
    default:
      return null;
  }
}
