/*
 * 앱 진입점(`/`)의 갈림길 (팀 결정 2026-09-14, S15P21E106-319).
 *
 * 앱을 열면 스플래시가 덮이고, 걷히면 로그인하지 않은 사람은 0.1 시작 화면을, 로그인한
 * 사람은 **3.1 취향 필름**을 본다.
 *
 * 전에는 로그인한 사람에게 2.1 홈을 그렸다. 업무 -461에서 **홈을 없애고 필름을 시작
 * 화면으로 정하면서**(사용자 결정 2026-09-16) 이 자리는 화면을 그리지 않고 `/film`으로
 * 보내기만 한다. 주소가 `/film`이 되어야 하단 내비의 `필름`이 선택된 채로 보인다.
 *
 * - 세션을 되살리는 중(`restoring`)에는 아무것도 그리지 않는다. 그 사이 필름을 그렸다가
 *   시작 화면으로 튕기면 화면이 한 번 갈린다. 이 시간은 스플래시가 덮고 있다
 * - 둘 다 `replace`로 보낸다. 뒤로 가기가 `/`로 돌아와 다시 튕기지 않게 한다
 *
 * **`/`만 가른다.** `/film` · `/movies/5` 같은 다른 주소로 바로 들어오는 경우는 그대로 둔다.
 * 와이어프레임 이동표의 `비로그인 ⇢ 0.6`(모든 회원 화면 보호)은 여전히 범위 밖이다 — 넣으면
 * 목업 미리보기에서 모든 화면이 튕긴다.
 */
import { Navigate } from 'react-router';
import { useAuth } from '../contexts/authContext';
import { path } from './paths';

export default function HomeEntry() {
  const { status } = useAuth();

  if (status === 'restoring') {
    return null;
  }

  return <Navigate to={status === 'anonymous' ? path.authStart() : path.film()} replace />;
}
