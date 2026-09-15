/*
 * 3.3 발급 화면이 떠 있는 동안 body 벽을 버건디로 바꾼다(업무 -321).
 *
 * 버건디는 화면 요소가 아니라 body 한 곳에서만 칠한다. 요소에 칠하면 넓은 창에서 앱 폭 바깥이
 * 공통 벽으로 남고, 요소가 끝나는 자리에 선이 생긴다. 화면을 떠나면 표시를 뗀다. 색은
 * `global.css`의 `body[data-wall='report']`다.
 */
import { useEffect } from 'react';

export function useReportWall(enabled = true) {
  useEffect(() => {
    if (!enabled) return;

    document.body.dataset.wall = 'report';

    return () => {
      delete document.body.dataset.wall;
    };
  }, [enabled]);
}
