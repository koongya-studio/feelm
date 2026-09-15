/*
 * OTT 표시 규칙.
 *
 * 와이어프레임은 OTT를 로고 대신 한 글자로 표시한다(common-components.md 4.4).
 * 계약에는 이 글자가 없으므로 코드에서 만든다.
 *
 * 지원 OTT가 다섯 개로 확정되면서 **첫 글자가 겹치는 OTT가 실제로 들어왔다**
 * (WATCHA · WAVVE). 기준정보에 표시 글자가 없는 것은 그대로라 화면은 API-025가 주는
 * 로고를 쓰고(`OttBadge`의 `logoUrl`), 이 함수는 로고가 없거나 불러오지 못한 자리의
 * 대체 표시다. 겹치는 왓챠만 한글 첫 글자로 구분한다 — 와이어프레임의 N · D · W · T는
 * 그대로 두고 충돌한 하나만 바꾸는 쪽이 기존 표기를 덜 흔든다.
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
