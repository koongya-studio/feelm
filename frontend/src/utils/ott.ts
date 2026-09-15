/*
 * OTT 표시 규칙.
 *
 * 와이어프레임은 OTT를 로고 대신 한 글자로 표시한다(common-components.md 4.4).
 * 계약에는 이 글자가 없으므로 코드의 첫 글자를 쓴다.
 * NETFLIX N · TVING T · WAVVE W · DISNEY_PLUS D — 와이어프레임 표기와 일치한다.
 *
 * `[?]` 지원 OTT 목록은 API-025 미정 사항이다. 첫 글자가 겹치는 OTT(WAVVE · WATCHA)가
 * 함께 지원되면 이 규칙만으로는 구분할 수 없어 기준정보에 표시 글자가 필요해진다.
 */
export function ottBadgeLetter(code: string): string {
  return code.charAt(0);
}
