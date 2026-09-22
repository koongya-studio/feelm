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
 * **폭을 다시 정하면 듣고 있던 쪽에 알린다**(업무 -787). 처음에는 알리지 않았는데, 그러면
 * 창을 끌어 폭이 1024px 경계를 지나는 순간 — 껍데기가 통째로 갈리고 화면이 다시 그려지는
 * 그 순간 — 을 시험에서 만들 수 없었다. 첫 폭만 확인하는 시험은 이 함수를 한 번만 부르므로
 * 달라지는 것이 없다.
 *
 * 쓰고 나면 `resetViewport()`로 되돌린다. 남겨 두면 뒤에 도는 테스트가 데스크톱 셸을 본다.
 */
const original = window.matchMedia;

/** 지금 폭. `matches`가 이 값을 그때그때 읽어, 먼저 만들어 둔 질의도 새 폭으로 답한다 */
let current = 0;
const listeners = new Set<() => void>();

export function setViewportWidth(width: number) {
  current = width;

  window.matchMedia = ((query: string) => {
    const min = /min-width:\s*(\d+)px/.exec(query);

    return {
      get matches() {
        return min ? current >= Number(min[1]) : false;
      },
      media: query,
      onchange: null,
      addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
      removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    };
  }) as unknown as typeof window.matchMedia;

  listeners.forEach((listener) => listener());
}

export function resetViewport() {
  window.matchMedia = original;
  listeners.clear();
  current = 0;
}
