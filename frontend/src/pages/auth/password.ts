/*
 * 비밀번호 규칙. 0.4 이메일 회원가입과 0.7-A 비밀번호 재설정이 함께 쓴다.
 *
 * 와이어프레임 0.7-A가 화면에 적어 둔 `8자 이상, 영문과 숫자 포함`이 유일한 근거다.
 * 두 화면이 쓰므로 화면 폴더가 아니라 인증 화면 묶음 아래에 둔다
 * (`common-components.md` 3.1). 화면 파일에 두지 않는 이유는 화면 컴포넌트만
 * 내보내야 하기 때문이다(`react-refresh/only-export-components`).
 *
 * `[?]` 특수문자 요구 · 최대 길이 · 사용할 수 없는 문자는 계약에 없다. 화면이 적어 둔
 * 것만 검사하고 임의로 늘리지 않는다.
 */

/** 화면에 보이는 문구. 검사 규칙과 어긋나지 않도록 한 곳에서 쓴다 */
export const PASSWORD_HINT = '8자 이상, 영문과 숫자 포함';

const MIN_LENGTH = 8;

/** 8자 이상이면서 영문과 숫자를 모두 포함하는지 */
export function isValidPassword(value: string) {
  return value.length >= MIN_LENGTH && /[A-Za-z]/.test(value) && /[0-9]/.test(value);
}
