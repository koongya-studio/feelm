/*
 * 테스트에서 화면 폭을 정한다 (업무 S15P21E106-782).
 *
 * 데스크톱 셸은 `matchMedia`로 폭을 읽는다(`hooks/useShellSize`). jsdom의 기본 `matchMedia`는
 * `setup.ts`가 채워 넣은 것이고 늘 "맞지 않음"으로 답하므로, 폭에 따라 갈리는 것을 확인하려면
 * 그 자리에 폭을 아는 것을 놓아야 한다.
 *
 * `min-width`만 해석한다. 셸이 그것만 쓴다 — `prefers-reduced-motion` 같은 다른 질의에는
 * 기본값대로 "맞지 않음"으로 답한다.
 *
 * 쓰고 나면 `resetViewport()`로 되돌린다. 남겨 두면 뒤에 도는 테스트가 데스크톱 셸을 본다.
 */
const original = window.matchMedia;

export function setViewportWidth(width: number) {
  window.matchMedia = ((query: string) => {
    const min = /min-width:\s*(\d+)px/.exec(query);

    return {
      matches: min ? width >= Number(min[1]) : false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    };
  }) as unknown as typeof window.matchMedia;
}

export function resetViewport() {
  window.matchMedia = original;
}
