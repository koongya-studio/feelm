/*
 * 화면 폭이 셸의 어느 단계에 있는지 (업무 S15P21E106-782).
 *
 * 데스크톱 셸의 뼈대는 CSS로만 세울 수 없다. 좌측 내비와 상단 바는 화면 안의 자리가 아니라
 * **다른 컴포넌트**이고, 우측 보조 패널은 페이지가 내용을 밀어 넣는 자리다. `display: none`으로
 * 양쪽을 모두 그려 두면 모바일에서도 상단 바가 읽지 않은 알림 수를 받으러 가고, 같은 역할의
 * 내비가 DOM에 둘 남는다. 그래서 그릴 것을 JS에서 고른다.
 *
 * **기준점 두 수의 원본은 여기다.** CSS 미디어 쿼리는 변수를 읽지 못해 토큰으로 둘 수 없고,
 * 읽히지 않는 토큰을 남겨 두면 그것을 고치고 반영됐다고 착각하게 된다. 같은 수를 다시 적는
 * 곳은 `styles/tokens.css`의 `@media (min-width: 1024px)` 하나뿐이다 — 1024px을 바꾸면 그곳도
 * 함께 바꾼다. 1280px은 이 파일에만 있다.
 *
 * `useSyncExternalStore`를 쓴다. 폭은 React 바깥에서 바뀌는 값이고, `useState` + `useEffect`로
 * 읽으면 첫 그림이 늘 모바일이라 데스크톱에서 셸이 한 번 갈린다.
 */
import { useSyncExternalStore } from 'react';

/** 하단 내비가 좌측 내비로 바뀌는 폭 */
export const SHELL_DESKTOP_MIN = 1024;
/** 우측 보조 패널 자리가 열리는 폭 */
export const SHELL_WIDE_MIN = 1280;

/**
 * - `mobile` — 지금의 모바일 셸 그대로다. 412px 폭 · 하단 내비
 * - `desktop` — 좌측 내비 · 상단 바 · 중앙 한 단
 * - `wide` — 위에 우측 보조 패널이 더 붙는다
 */
export type ShellSize = 'mobile' | 'desktop' | 'wide';

const DESKTOP_QUERY = `(min-width: ${SHELL_DESKTOP_MIN}px)`;
const WIDE_QUERY = `(min-width: ${SHELL_WIDE_MIN}px)`;

function read(): ShellSize {
  if (window.matchMedia(WIDE_QUERY).matches) return 'wide';
  if (window.matchMedia(DESKTOP_QUERY).matches) return 'desktop';

  return 'mobile';
}

function subscribe(onChange: () => void) {
  const queries = [window.matchMedia(DESKTOP_QUERY), window.matchMedia(WIDE_QUERY)];

  queries.forEach((query) => query.addEventListener('change', onChange));

  return () => queries.forEach((query) => query.removeEventListener('change', onChange));
}

/**
 * 서버에서 그릴 때의 값은 `mobile`이다. 이 앱은 브라우저에서만 그리지만, 폭을 모르는 자리에서는
 * 좁은 쪽을 고르는 것이 안전하다 — 넓은 쪽을 고르면 휴대폰이 한 번 데스크톱 셸을 그렸다가 접는다.
 */
function readServer(): ShellSize {
  return 'mobile';
}

export function useShellSize(): ShellSize {
  return useSyncExternalStore(subscribe, read, readServer);
}
