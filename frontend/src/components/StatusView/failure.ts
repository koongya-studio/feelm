/*
 * 실패 원인을 `StatusView`의 보조 문구로 옮긴다 (업무 S15P21E106-374).
 *
 * 제목은 화면이 정한다 — **무엇을** 못 했는지는 화면마다 다르다(`프로필을 불러오지
 * 못했어요`). 보조 문구는 **왜** 못 했고 사용자가 **무엇을 하면** 되는지이며, 그것은
 * `ApiError`가 알고 있어 화면마다 다시 쓸 이유가 없다(`common-components.md` 6.6 —
 * 오류는 문제와 회복 방법을 말한다).
 *
 * 구분은 둘까지다. 사용자가 할 일이 갈리는 지점이 거기까지라서다
 * (`frontend-structure.md` 7.4).
 * - 요청이 서버에 닿지 못했다 → 사용자 쪽 연결을 보라고 한다
 * - 서버가 응답했지만 처리하지 못했다 → 기다렸다가 다시 시도하라고 한다
 *
 * 503과 500을 다시 나누지 않는다. 어느 쪽이든 사용자가 할 수 있는 일은 같다.
 *
 * 화면까지 온 401도 서버 실패로 본다. 세션이 정말 끝났으면 클라이언트가 세션을 닫고
 * `AuthProvider`가 비로그인으로 되돌리므로 화면이 그 401을 볼 일이 없다. 화면에 닿은
 * 401은 재발급이 서버 사정(5xx · 연결 실패)으로 안 된 경우다(`api/session.ts`).
 *
 * 그 밖의 4xx는 서버의 `message`를 그대로 쓴다. 계약이 그 문구를 사용자에게 보여줄 수
 * 있게 정했고(`docs/api/README.md` 오류 응답), 거절 사유는 서버가 가장 정확히 안다.
 * 화면이 특정 코드에 자기 문구를 두고 싶으면(0.6의 `INVALID_CREDENTIALS`) 이 함수를
 * 부르기 전에 가른다.
 */
import { ApiError, NETWORK_ERROR, UNEXPECTED_ERROR } from '../../api/apiError';

export const NETWORK_FAILURE_DESCRIPTION = '네트워크 연결을 확인하고 다시 시도해 주세요';

export const SERVER_FAILURE_DESCRIPTION = '서버에 잠시 문제가 생겼어요. 잠시 후 다시 시도해 주세요';

/** 실패의 원인과 할 일. `StatusView`의 `description`이나 폼의 실패 문구에 넣는다 */
export function failureDescription(error: unknown): string {
  if (!(error instanceof ApiError)) {
    // 화면 코드의 예외 등 계약 밖의 실패. 사용자에게 내부 사정을 말할 방법이 없다
    return SERVER_FAILURE_DESCRIPTION;
  }

  if (error.code === NETWORK_ERROR) {
    return NETWORK_FAILURE_DESCRIPTION;
  }

  if (error.status >= 500 || error.status === 401 || error.code === UNEXPECTED_ERROR) {
    return SERVER_FAILURE_DESCRIPTION;
  }

  return error.message || SERVER_FAILURE_DESCRIPTION;
}
