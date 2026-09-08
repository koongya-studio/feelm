/*
 * 날짜 표시 형식.
 *
 * 와이어프레임 3.1-A는 감상 날짜를 `2026. 8. 24`로 적는다. 3.1의 `최근 추가된 영화`는
 * `어제` · `3일 전` · `지난주` 같은 상대 시각으로 적지만, 상대 시각으로 바꾸는 규칙이
 * 계약에 없어 같은 절대 날짜 형식으로 표시하기로 정했다(팀 결정 2026-09-07).
 *
 * `toLocaleDateString('ko-KR')`은 실행 환경에 따라 결과가 달라지고 끝에 점이 하나 더
 * 붙으므로 직접 조립한다.
 */

/** ISO 8601 문자열을 `2026. 8. 24` 형식으로 만든다 */
export function formatDate(isoString: string): string {
  const date = new Date(isoString);

  if (Number.isNaN(date.getTime())) {
    return '';
  }

  return `${date.getFullYear()}. ${date.getMonth() + 1}. ${date.getDate()}`;
}

/**
 * ISO 8601 문자열을 `6월 2일` 형식으로 만든다.
 *
 * 2.5의 감상 정보 줄(`6월 2일 · 넷플릭스에서 시청`)이 쓴다. 3.1 · 3.1-A와 형식이 다른
 * 것은 와이어프레임이 화면마다 다르게 적기 때문이며, 연도 없이 적는 이유는 최근 감상을
 * 가리키기 때문으로 보인다. 통일 여부는 결정되지 않았다.
 */
export function formatMonthDay(isoString: string): string {
  const date = new Date(isoString);

  if (Number.isNaN(date.getTime())) {
    return '';
  }

  return `${date.getMonth() + 1}월 ${date.getDate()}일`;
}
