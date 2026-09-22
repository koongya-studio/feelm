/*
 * 테스트 환경 준비 (업무 S15P21E106-333).
 *
 * `@testing-library/jest-dom`의 단정문(`toBeInTheDocument` 등)을 등록하고, 각 테스트
 * 뒤에 렌더한 DOM을 치운다. 치우지 않으면 같은 `aria-label`을 가진 요소가 여러 개
 * 남아 조회가 실패한다.
 *
 * jsdom에 없는 브라우저 기능은 여기서 채운다. 화면 코드에서 쓰는 것만 최소로 둔다.
 */
import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
});

/*
 * 기다리는 단정문의 예산 (업무 S15P21E106-797).
 *
 * `findBy*` · `waitFor`의 기본 예산은 1초다. 이 값은 **조건이 참이 되는가가 아니라 얼마나 빨리
 * 참이 되는가**를 재며, 뒤엣것은 화면이 아니라 기계 사정으로 정해진다. 전체 실행은 100개가
 * 넘는 파일을 동시에 돌리므로 일꾼 하나가 받는 몫이 작고, 기계가 바쁘면 같은 기다림이 몇 배로
 * 늘어난다.
 *
 * 부하를 준 전체 실행에서 기다림 823개 중 28개가 1초를 넘겼다(가장 오래 걸린 것 2.1초).
 * 부하가 없을 때는 12개이고 그마저 예산을 따로 적어 둔 장면 시험이다. 즉 **1초는 바쁜 기계에서
 * 여유가 없고, 어느 시험이 먼저 넘길지는 그때그때 다르다** — -789가 고친 둘과 -797이 만난 둘이
 * 서로 다른 시험이었던 까닭이다.
 *
 * 예산을 늘려도 단정문은 그대로다. 조건이 끝내 참이 되지 않으면 여전히 깨지고, 늘어난 시간은
 * 깨지는 시험만 치른다.
 */
configure({ asyncUtilTimeout: 5_000 });

/*
 * `matchMedia`는 jsdom에 없다. `prefers-reduced-motion`을 읽는 코드가 있으면
 * 여기서 걸린다. 항상 "선호 없음"으로 답한다.
 */
if (!window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

/*
 * `ResizeObserver`도 jsdom에 없다. 1.1의 배치 영역이 크기를 재는 데 쓴다.
 * 관측만 받고 아무것도 통보하지 않는다 — 테스트는 크기에 의존하지 않는다.
 */
if (!window.ResizeObserver) {
  window.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

/*
 * `document.fonts`(FontFaceSet)도 jsdom에 없다. 스플래시가 서체 로드를 준비 신호로
 * 쓰므로(`components/SplashScreen`) 없으면 그 화면이 여기서 터진다. 이미 로드된
 * 것으로 답해, 테스트가 서체를 기다리며 멈추지 않게 한다.
 */
if (!document.fonts) {
  Object.defineProperty(document, 'fonts', {
    configurable: true,
    value: { ready: Promise.resolve() },
  });
}
