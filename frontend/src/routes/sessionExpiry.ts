/*
 * 세션이 만료되면 로그인 화면으로 보낸다 (업무 S15P21E106-596).
 *
 * 재발급이 401로 거절되면 다시 로그인하는 것 말고는 방법이 없다. 그런데 화면은 그대로
 * 남아 있어서, 사용자는 로그인 상태라고 믿은 채 모든 요청이 실패하는 것만 본다. 2026-09-17
 * 여정 QA의 D-3이 그 상태였다 — "다시 시도"를 눌러도 재발급조차 하지 않고 장애 문구만
 * 반복됐다. 세션이 없다는 판정은 `api/session`이 이미 내리고 있었고(업무 -373), 그 판정을
 * 쓰는 곳이 없었을 뿐이다.
 *
 * 라우트 보호 계층은 여기서 만들지 않는다(`routes/router.tsx`의 범위 밖 표기 그대로).
 * 이동을 부르는 것은 "세션이 끊겼다"는 사건 하나뿐이다.
 *
 * 이동 함수를 인자로 받는 이유는 이 배선이 라우터 인스턴스에 묶이지 않게 하기 위해서다.
 * 덕분에 앱 전체를 띄우지 않고도 "언제 이동하는가"를 확인할 수 있다.
 */
import { onSessionEnd } from '../api/session';
import { path } from './paths';

export type SessionExpiryNavigate = (
  to: string,
  options: { replace: true; state: { sessionExpired: true } },
) => void;

/** 로그인 화면이 만료 안내를 띄울지 판단하는 값 */
export type SessionExpiryState = { sessionExpired?: boolean };

/** 구독을 끊는 함수를 돌려준다. `useEffect`의 정리 함수로 그대로 쓴다 */
export function redirectOnSessionExpiry(navigate: SessionExpiryNavigate): () => void {
  return onSessionEnd((reason) => {
    /*
     * 로그아웃도 세션을 끝내지만 그것은 사용자가 시킨 일이고, 이동은 그 화면이 이미
     * 하고 있다(`SettingsPage`·`WithdrawPage`·`PasswordChangePage`). 여기서 또 보내면
     * 그 화면들의 이동과 겹친다.
     */
    if (reason !== 'expired') {
      return;
    }

    navigate(path.login(), { replace: true, state: { sessionExpired: true } });
  });
}
