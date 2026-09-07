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

/** 마지막 글자에 받침이 있는지. 한글 음절이 아니면 false */
function hasFinalConsonant(word: string): boolean {
  const last = word.trim().at(-1);

  if (!last) {
    return false;
  }

  const code = last.charCodeAt(0);

  if (code < HANGUL_START || code > HANGUL_END) {
    return false;
  }

  return (code - HANGUL_START) % 28 !== 0;
}

/** 주격 조사 `이` / `가` */
export function subjectParticle(word: string): string {
  return hasFinalConsonant(word) ? '이' : '가';
}

/** 접속 조사 `과` / `와` */
export function conjunctionParticle(word: string): string {
  return hasFinalConsonant(word) ? '과' : '와';
}
