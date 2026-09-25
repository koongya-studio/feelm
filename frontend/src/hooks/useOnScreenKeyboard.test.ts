/*
 * 화면 키보드 감지 (업무 S15P21E106-718 · -882)
 *
 * 폰에서 입력 칸을 누르면 OS 키보드가 화면 아래를 덮어 가입 화면의 타자기와 겹쳤다.
 * 기종마다 키보드가 화면을 줄이는 방식이 달라, 718의 계산(`innerHeight - 보이는 높이`)은
 * 안드로이드에서 키보드를 못 알아채고 iOS에서 보이는 영역을 옮기면 크게 잡았다(업무 -882).
 *
 * 고정할 것
 * - 안드로이드(`resizes-content`) — 레이아웃 높이와 보이는 높이가 함께 줄어도 열림으로 본다
 * - iOS — 보이는 영역만 줄고, 초점 칸으로 옮기면(`offsetTop`) 아래 끝이 그만큼 내려간다
 * - iOS 자동 확대 — 보이는 영역의 폭과 높이가 배율만큼 함께 줄어도 회전으로 읽지 않고 키보드를
 *   알아챈다(!679 리뷰)
 * - 입력 칸에 초점이 없으면 창이 줄어도(화면 분할) 키보드가 아니다
 * - 칸에서 칸으로 옮기는 사이 잠깐 빈 초점을 닫힘으로 읽지 않는다
 * - 주소 표시줄 정도의 움직임은 키보드가 아니다 · PC와 `visualViewport` 없는 환경은 그대로다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useOnScreenKeyboard } from './useOnScreenKeyboard';

type Listener = () => void;

/** `visualViewport`를 세우고 크기 · 위치를 바꿀 수 있게 돌려준다 */
function stubViewport({ width = 390, height }: { width?: number; height: number }) {
  const listeners = new Map<string, Set<Listener>>();
  // 레이아웃 폭(jsdom은 `documentElement.clientWidth`가 0이라 `innerWidth`로 읽는다)
  vi.stubGlobal('innerWidth', width);
  const viewport = {
    width,
    height,
    offsetTop: 0,
    scale: 1,
    addEventListener: (type: string, listener: Listener) => {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type)!.add(listener);
    },
    removeEventListener: (type: string, listener: Listener) =>
      void listeners.get(type)?.delete(listener),
  };

  Object.defineProperty(window, 'visualViewport', { value: viewport, configurable: true });

  const fire = (type: string) => act(() => listeners.get(type)?.forEach((listener) => listener()));

  return {
    /** 보이는 높이를 바꾼다(`resize`). 안드로이드는 레이아웃 높이도 함께 바꾼다 */
    resizeTo(next: number, { layout }: { layout?: number } = {}) {
      viewport.height = next;
      if (layout !== undefined) vi.stubGlobal('innerHeight', layout);
      fire('resize');
    },
    /** iOS가 초점 칸을 보이려 보이는 영역을 옮긴다(`scroll`) */
    panTo(offsetTop: number) {
      viewport.offsetTop = offsetTop;
      fire('scroll');
    },
    /** iOS가 입력 칸을 키운다. 보이는 영역의 폭 · 높이가 배율만큼 함께 준다 */
    zoomTo(scale: number, visibleHeight: number) {
      viewport.scale = scale;
      viewport.width = width / scale;
      viewport.height = visibleHeight / scale;
      fire('resize');
    },
    rotate(nextWidth: number, nextHeight: number) {
      vi.stubGlobal('innerWidth', nextWidth);
      viewport.width = nextWidth;
      viewport.height = nextHeight;
      fire('resize');
    },
    get listenerCount() {
      return [...listeners.values()].reduce((sum, set) => sum + set.size, 0);
    },
  };
}

let input: HTMLInputElement;

beforeEach(() => {
  input = document.createElement('input');
  document.body.append(input);
});

afterEach(() => {
  input.remove();
  Object.defineProperty(window, 'visualViewport', { value: undefined, configurable: true });
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

/** 입력 칸을 누른다. 화면 키보드는 이때만 뜬다 */
const focusInput = () => act(() => input.focus());

describe('화면 키보드 감지', () => {
  it('iOS — 보이는 영역만 줄면 열림이고, 덮인 높이와 보이는 아래 끝을 준다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ height: 812 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    expect(result.current).toEqual({ open: false, inset: 0, visibleBottom: null });

    focusInput();
    viewport.resizeTo(476);

    expect(result.current).toEqual({ open: true, inset: 336, visibleBottom: 476 });
  });

  it('iOS — 초점 칸으로 보이는 영역을 옮기면 아래 끝도 그만큼 내려간다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ height: 812 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    viewport.resizeTo(476);
    viewport.panTo(120);

    // 718은 여기서도 336을 주어 종이가 키보드보다 120px 높이 떴다
    expect(result.current).toEqual({ open: true, inset: 216, visibleBottom: 596 });
  });

  it('iOS 자동 확대 — 폭 · 높이가 배율만큼 함께 줄어도 회전이 아니라 키보드로 본다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ width: 390, height: 812 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    // 15px 칸에 초점이 가 16/15배로 키우고 키보드(336)가 올라왔다. 레이아웃 폭은 390 그대로다
    viewport.zoomTo(16 / 15, 476);

    // 보이는 폭(365.6)만 보고 회전으로 읽으면 줄어든 높이가 기준이 되어 닫힘이었다
    expect(result.current.open).toBe(true);
    // 아래 끝은 CSS px 그대로다(476 / 배율)
    expect(result.current.visibleBottom).toBe(Math.round(476 / (16 / 15)));
  });

  it('iOS 자동 확대만으로는(키보드 없이) 열림이 아니다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ width: 390, height: 812 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    viewport.zoomTo(16 / 15, 812);

    expect(result.current.open).toBe(false);
  });

  it('안드로이드 — 레이아웃 높이와 보이는 높이가 함께 줄어도 열림으로 본다', () => {
    vi.stubGlobal('innerHeight', 780);
    const viewport = stubViewport({ width: 412, height: 780 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    viewport.resizeTo(470, { layout: 470 });

    // 718은 두 값의 차이(0)만 보아 닫힘으로 읽었다. 레이아웃이 이미 줄어 덮인 높이는 없다
    expect(result.current).toEqual({ open: true, inset: 0, visibleBottom: 470 });

    viewport.resizeTo(780, { layout: 780 });
    expect(result.current.open).toBe(false);
  });

  it('입력 칸에 초점이 없으면 창이 줄어도 키보드로 보지 않는다 — 화면 분할', () => {
    vi.stubGlobal('innerHeight', 780);
    const viewport = stubViewport({ width: 412, height: 780 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    viewport.resizeTo(420, { layout: 420 });

    expect(result.current.open).toBe(false);
  });

  it('칸에서 칸으로 옮기는 사이 잠깐 빈 초점을 닫힘으로 읽지 않는다', () => {
    vi.useFakeTimers();
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ height: 812 });
    const next = document.createElement('input');
    document.body.append(next);
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    viewport.resizeTo(476);

    act(() => {
      input.blur();
      next.focus();
      vi.runAllTimers();
    });

    expect(result.current.open).toBe(true);
    next.remove();
  });

  it('초점이 빠지고 키보드가 닫히면 닫힘으로 돌아온다', () => {
    vi.useFakeTimers();
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ height: 812 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    viewport.resizeTo(476);

    act(() => {
      input.blur();
      vi.runAllTimers();
    });
    viewport.resizeTo(812);

    expect(result.current).toEqual({ open: false, inset: 0, visibleBottom: null });
  });

  it('주소 표시줄이 접히는 정도로는 키보드로 보지 않는다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ height: 812 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    viewport.resizeTo(720);

    expect(result.current.open).toBe(false);
  });

  it('화면을 돌리면 그 방향의 높이를 기준으로 다시 잰다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ width: 375, height: 812 });
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();
    // 가로로 돌리면 보이는 높이가 크게 줄지만 키보드가 아니다
    vi.stubGlobal('innerHeight', 375);
    viewport.rotate(812, 375);
    expect(result.current.open).toBe(false);

    viewport.resizeTo(170);
    expect(result.current.open).toBe(true);
  });

  it('`visualViewport`가 없으면 늘 닫힌 것으로 본다 — 화면이 지금까지와 같다', () => {
    vi.stubGlobal('innerHeight', 812);
    const { result } = renderHook(() => useOnScreenKeyboard());

    focusInput();

    expect(result.current.open).toBe(false);
  });

  it('떠날 때 듣던 것을 끊는다', () => {
    vi.stubGlobal('innerHeight', 812);
    const viewport = stubViewport({ height: 812 });
    const { unmount } = renderHook(() => useOnScreenKeyboard());

    expect(viewport.listenerCount).toBe(2);

    unmount();
    expect(viewport.listenerCount).toBe(0);
  });
});
