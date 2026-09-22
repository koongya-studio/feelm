/*
 * 스크롤한 만큼을 0~1로 요소의 CSS 변수에 적는다(업무 -697).
 *
 * 사진 배경 화면(2.4 영화 상세 · 7.1 · 7.2 프로필)이 배경을 스크롤에 따라 흐리고 어둡게 하는 데
 * 쓴다. 값은 `window.innerHeight × ratio`만큼 내려가면 1이 된다.
 *
 * **스크롤하는 것이 창이 아닐 수 있다.** 2.4는 전체화면 틀이라 창이 스크롤하지만, 하단 내비가
 * 있는 틀(`AppLayout`)은 안쪽 영역이 스크롤한다. 그래서 요소에서 가장 가까운 스크롤 영역을
 * 찾아 듣고, 없으면 창을 듣는다.
 */
import { useEffect } from 'react';
import type { DependencyList, RefObject } from 'react';

/**
 * 요소를 감싼 가장 가까운 세로 스크롤 영역. 없으면 `null`(창이 스크롤한다).
 *
 * 내보내는 이유는 스크롤 위치를 **읽는** 것 말고 **옮겨야** 하는 화면이 있어서다. 2.4 영화
 * 상세가 영화를 바꿀 때 맨 위로 되돌리고 `맨 위로` 단추로 올라간다(업무 -787). 같은 판정을
 * 두 벌 두면 한쪽만 고치게 된다.
 */
export function scrollParent(node: HTMLElement): HTMLElement | null {
  let current = node.parentElement;

  while (current) {
    const { overflowY } = window.getComputedStyle(current);

    if (overflowY === 'auto' || overflowY === 'scroll') return current;
    current = current.parentElement;
  }

  return null;
}

export function useScrollFade(
  ref: RefObject<HTMLElement | null>,
  variable: string,
  ratio: number,
  deps: DependencyList = [],
) {
  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    const scroller = scrollParent(node);
    const target: HTMLElement | Window = scroller ?? window;
    let frame = 0;

    const apply = () => {
      frame = 0;
      const end = window.innerHeight * ratio;
      const top = scroller ? scroller.scrollTop : window.scrollY;
      const progress = end > 0 ? Math.min(1, top / end) : 1;
      node.style.setProperty(variable, progress.toFixed(3));
    };
    const onScroll = () => {
      if (frame === 0) frame = window.requestAnimationFrame(apply);
    };

    apply();
    target.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });

    return () => {
      target.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      if (frame !== 0) window.cancelAnimationFrame(frame);
    };
    // 호출하는 쪽이 넘긴 의존성으로 다시 붙는다(데이터가 와서 요소가 생긴 뒤 등)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref, variable, ratio, ...deps]);
}
