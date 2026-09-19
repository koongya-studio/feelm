/*
 * 뒤로 가기로 돌아오면 보던 자리로 (업무 S15P21E106-520).
 *
 * 하단 내비가 있는 화면은 문서가 아니라 `AppLayout`의 본문(`.body`)이 스크롤한다. 브라우저의
 * 기본 복원도, react-router의 `ScrollRestoration`도 문서 스크롤을 전제로 해서 이 구조에는
 * 관여하지 않는다. 그래서 3.1에서 내려 보다가 4.1 검색이나 3.1-A에 다녀오면 맨 위로 돌아가
 * 있었다 — 화면을 떠나면 React가 그 화면을 버리고 돌아올 때 처음부터 다시 그린다.
 *
 * 규칙은 둘이다.
 * - **뒤로 · 앞으로(`POP`)로 돌아오면** 그 히스토리 항목에서 보던 자리로 되돌린다
 * - **그 밖의 이동(하단 탭 · 링크 · `replace`)은 맨 위에서 시작한다.** 탭을 누르는 것은 그
 *   화면을 새로 여는 것이다. 본문 요소는 탭 사이에서 그대로 남으므로, 되돌리지 않으면 앞 화면의
 *   스크롤 값이 새 화면에 그대로 묻어 온다
 *
 * 자리는 히스토리 항목(`location.key`)마다 기억한다. 같은 3.1이라도 두 번 들어갔으면 항목이
 * 둘이다. 기억은 모듈에 둔다 — 전체화면(2.4 · 3.1-A)으로 가면 `AppLayout`이 통째로 사라진다.
 *
 * 돌아온 화면은 데이터를 다시 받는 중이라 처음에는 짧다. 짧은 동안에는 브라우저가 스크롤을 잘라
 * 버리므로, 목표에 닿을 때까지 프레임마다 다시 맞춘다. 사용자가 먼저 스크롤을 건드리거나 시간이
 * 다 되면 멈춘다 — 손으로 움직인 자리를 빼앗지 않는다.
 *
 * 되돌리는 것은 스크롤 위치까지다. 목록에서 고른 항목 강조처럼 화면마다 다른 상태는 다루지 않는다.
 * 4.1 검색은 안쪽 목록이 스스로 스크롤하고 자기 복원(`searchSnapshot`)이 있어 본문은 움직이지 않는다.
 */
import { useLayoutEffect } from 'react';
import { useLocation, useNavigationType } from 'react-router';
import type { RefObject } from 'react';

/** 히스토리 항목마다 본문에서 보던 자리 */
const positions = new Map<string, number>();

/**
 * 돌아온 화면이 목표 높이까지 자라기를 기다리는 한도. 데이터가 느린 날(요청 하나에 수 초)도
 * 있어 넉넉히 둔다. 그 안에 사용자가 스크롤하면 바로 멈춘다
 */
const RESTORE_TIMEOUT_MS = 5000;

/** 사용자가 스크롤을 건드렸다는 신호. 이 중 하나가 오면 되돌리기를 멈춘다 */
const USER_SCROLL_EVENTS = ['wheel', 'touchstart', 'pointerdown', 'keydown'] as const;

/** 시험이 항목 사이의 기억을 비운다 */
export function resetScrollPositions() {
  positions.clear();
}

export function useScrollRestoration(ref: RefObject<HTMLElement | null>) {
  const { key } = useLocation();
  const navigationType = useNavigationType();

  /*
   * 레이아웃 효과다. 화면이 바뀌어 본문이 짧아지면 브라우저가 스크롤을 잘라 `scroll` 이벤트를
   * 낸다. 앞 항목의 기록을 그 전에 떼어야 잘린 값이 앞 항목의 자리로 남지 않는다.
   */
  useLayoutEffect(() => {
    const element = ref.current;

    if (!element) return;

    let restoring = false;
    let frame = 0;
    const target = navigationType === 'POP' ? positions.get(key) : undefined;

    const record = () => {
      // 되돌리는 중에 잘린 값을 기록하면 목표를 잃는다
      if (!restoring) positions.set(key, element.scrollTop);
    };

    const stop = () => {
      restoring = false;
      cancelAnimationFrame(frame);
      USER_SCROLL_EVENTS.forEach((type) => element.removeEventListener(type, stop));
    };

    element.addEventListener('scroll', record, { passive: true });

    if (target === undefined || target === 0) {
      element.scrollTop = 0;
    } else {
      restoring = true;
      const deadline = performance.now() + RESTORE_TIMEOUT_MS;

      USER_SCROLL_EVENTS.forEach((type) => element.addEventListener(type, stop, { passive: true }));

      const attempt = () => {
        element.scrollTop = target;

        if (Math.abs(element.scrollTop - target) <= 1 || performance.now() > deadline) {
          stop();
          return;
        }

        frame = requestAnimationFrame(attempt);
      };

      attempt();
    }

    return () => {
      stop();
      element.removeEventListener('scroll', record);
    };
  }, [ref, key, navigationType]);
}
