/*
 * 화면 키보드 감지 (업무 S15P21E106-718)
 *
 * 폰에서 입력 칸을 누르면 OS 키보드가 화면 아래를 덮어 가입 화면의 타자기와 겹쳤다.
 * 키보드는 레이아웃 뷰포트(`innerHeight`)를 바꾸지 않고 보이는 영역(`visualViewport`)만
 * 줄인다 — 그 차이로 알아낸다.
 *
 * **PC가 그대로여야 한다.** 키보드가 뜨지 않으면 두 값이 같으므로 아무 일도 없다.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useOnScreenKeyboard } from './useOnScreenKeyboard';

type FakeViewport = {
  height: number;
  addEventListener: (type: string, listener: () => void) => void;
  removeEventListener: (type: string, listener: () => void) => void;
};

/** `visualViewport`를 세우고 높이를 바꿀 수 있게 돌려준다 */
function stubViewport(height: number) {
  const listeners = new Set<() => void>();
  const viewport: FakeViewport = {
    height,
    addEventListener: (_type, listener) => void listeners.add(listener),
    removeEventListener: (_type, listener) => void listeners.delete(listener),
  };

  Object.defineProperty(window, 'visualViewport', { value: viewport, configurable: true });

  return {
    resizeTo(next: number) {
      viewport.height = next;
      act(() => {
        listeners.forEach((listener) => listener());
      });
    },
    get listenerCount() {
      return listeners.size;
    },
  };
}

afterEach(() => {
  Object.defineProperty(window, 'visualViewport', { value: undefined, configurable: true });
  vi.unstubAllGlobals();
});

describe('화면 키보드 감지', () => {
  it('보이는 영역이 크게 줄면 키보드가 올라온 것으로 보고 덮인 높이를 준다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport(812);

    const { result } = renderHook(() => useOnScreenKeyboard());

    expect(result.current).toEqual({ open: false, inset: 0 });

    viewport.resizeTo(480);
    // 덮인 높이는 화면과 보이는 영역의 차이다. 종이가 이만큼 위로 올라선다
    expect(result.current).toEqual({ open: true, inset: 332 });
  });

  it('키보드가 닫히면 되돌아온다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport(480);

    const { result } = renderHook(() => useOnScreenKeyboard());

    expect(result.current.open).toBe(true);

    viewport.resizeTo(812);
    expect(result.current.open).toBe(false);
  });

  it('주소 표시줄이 접히는 정도로는 키보드로 보지 않는다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport(812);

    const { result } = renderHook(() => useOnScreenKeyboard());

    viewport.resizeTo(720);
    expect(result.current).toEqual({ open: false, inset: 0 });
  });

  it('`visualViewport`가 없으면 늘 닫힌 것으로 본다 — 화면이 지금까지와 같다', () => {
    vi.stubGlobal('innerHeight', 812);

    const { result } = renderHook(() => useOnScreenKeyboard());

    expect(result.current.open).toBe(false);
  });

  it('떠날 때 듣던 것을 끊는다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport(812);

    const { unmount } = renderHook(() => useOnScreenKeyboard());

    expect(viewport.listenerCount).toBe(1);

    unmount();
    expect(viewport.listenerCount).toBe(0);
  });
});
