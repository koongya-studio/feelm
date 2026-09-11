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

/**
 * ISO 8601 문자열을 `2026.08` 형식으로 만든다.
 *
 * 7.1 MY 대시보드의 `2026.08 가입`이 쓴다(API-014 `createdAt`). 월을 두 자리로 맞추는
 * 것은 와이어프레임 표기 그대로이며, 위 두 형식과 달리 가입 시점은 일까지 필요하지 않다.
 */
export function formatJoinedMonth(isoString: string): string {
  const date = new Date(isoString);

  if (Number.isNaN(date.getTime())) {
    return '';
  }

  return `${date.getFullYear()}.${String(date.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * 생년월일(`YYYY-MM-DD`)을 `20대` 형식으로 만든다.
 *
 * 와이어프레임 7.2는 연령대를 보여주지만 **계약에는 연령대가 없다.** API-014가 주는
 * 것은 생년월일이고(실명은 수집하지 않는다), 화면이 바꿔 보여준다.
 *
 * 10세 단위로 내림한다. 10살 미만은 가입 대상이 아니지만 값이 이상하게 들어와도
 * 화면이 깨지지 않게 `10대`로 묶는다. 100세 이상은 `90대 이상`으로 끝낸다.
 */
export function formatAgeGroup(birthDate: string, today: Date = new Date()): string {
  const birth = new Date(birthDate);

  if (Number.isNaN(birth.getTime())) {
    return '';
  }

  let age = today.getFullYear() - birth.getFullYear();
  const monthDiff = today.getMonth() - birth.getMonth();

  // 생일이 아직 안 지났으면 한 살 뺀다
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) {
    age -= 1;
  }

  if (age < 20) {
    return '10대';
  }

  if (age >= 90) {
    return '90대 이상';
  }

  return `${Math.floor(age / 10) * 10}대`;
}
