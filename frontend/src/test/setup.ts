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
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
});

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
