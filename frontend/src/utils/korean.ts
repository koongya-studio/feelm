/*
 * 한국어 조사 선택.
 *
 * 3.2의 요약 문장은 `{맛1}와 {맛2}가 내 영화의 {N}%예요.` 형태로 조립한다. 맛 이름은
 * API-044가 내려주는 값이라 고정되어 있지 않고, 마지막 글자에 받침이 있으면 조사가
 * 달라진다. 문구를 만들 때 `스릴가`처럼 틀린 형태가 나오지 않게 여기서 고른다.
 *
 * 숫자나 영문으로 끝나는 이름은 판별할 수 없어 받침이 없는 쪽으로 둔다. 표시 전용이며
 * 서버로 보내는 값에는 쓰지 않는다.
 */

const HANGUL_START = 0xac00;
const HANGUL_END = 0xd7a3;

/** 마지막 글자의 받침 번호. 한글 음절이 아니거나 받침이 없으면 0 */
function finalConsonant(word: string): number {
  const last = word.trim().at(-1);

  if (!last) {
    return 0;
  }

  const code = last.charCodeAt(0);

  if (code < HANGUL_START || code > HANGUL_END) {
    return 0;
  }

  return (code - HANGUL_START) % 28;
}

/** 받침 ㄹ의 번호 */
const RIEUL = 8;

function hasFinalConsonant(word: string): boolean {
  return finalConsonant(word) !== 0;
}

/** 주격 조사 `이` / `가` */
export function subjectParticle(word: string): string {
  return hasFinalConsonant(word) ? '이' : '가';
}

/** 접속 조사 `과` / `와` */
export function conjunctionParticle(word: string): string {
  return hasFinalConsonant(word) ? '과' : '와';
}

/** 주제 조사 `은` / `는` */
export function topicParticle(word: string): string {
  return hasFinalConsonant(word) ? '은' : '는';
}

/**
 * 방향 · 수단 조사 `으로` / `로`. 받침 ㄹ은 `로`를 쓴다.
 *
 * 2.4-A의 `{OTT 이름}로 이동했어요`에 쓴다. OTT 이름은 API-025가 내려주는 값이라
 * `티빙으로`처럼 받침에 따라 조사가 달라진다.
 */
export function directionParticle(word: string): string {
  const final = finalConsonant(word);

  return final === 0 || final === RIEUL ? '로' : '으로';
}
