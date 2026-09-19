import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import PwaInstallProvider from './PwaInstallProvider';
import { isIosSafari, usePwaInstall } from './installContext';
import type { DeferredInstallPrompt } from './installContext';

function Probe() {
  const { availability, install } = usePwaInstall();

  return (
    <button type="button" onClick={() => void install()}>
      {availability}
    </button>
  );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('PWA 설치 상태', () => {
  it('브라우저 설치 이벤트를 보관했다가 사용자 동작에서 한 번만 사용한다', async () => {
    const prompt = vi.fn().mockResolvedValue(undefined);
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt,
      userChoice: Promise.resolve({ outcome: 'accepted' as const, platform: 'web' }),
    }) as DeferredInstallPrompt;

    render(
      <PwaInstallProvider>
        <Probe />
      </PwaInstallProvider>,
    );

    act(() => window.dispatchEvent(event));

    expect(event.defaultPrevented).toBe(true);
    expect(screen.getByRole('button', { name: 'prompt' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'prompt' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'installed' })).toBeInTheDocument(),
    );
    expect(prompt).toHaveBeenCalledTimes(1);
  });

  it('standalone 표시 모드에서는 설치 안내를 내놓지 않는다', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query) =>
        ({
          matches: query === '(display-mode: standalone)',
          media: query,
          onchange: null,
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
          addListener: vi.fn(),
          removeListener: vi.fn(),
          dispatchEvent: vi.fn(),
        }) as unknown as MediaQueryList,
    );

    render(
      <PwaInstallProvider>
        <Probe />
      </PwaInstallProvider>,
    );

    expect(screen.getByRole('button', { name: 'installed' })).toBeInTheDocument();
  });

  it('브라우저가 설치 UI를 거절해도 앱은 오류 없이 계속 동작한다', async () => {
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt: vi.fn().mockRejectedValue(new Error('prompt is no longer available')),
      userChoice: Promise.resolve({ outcome: 'dismissed' as const, platform: 'web' }),
    }) as DeferredInstallPrompt;

    render(
      <PwaInstallProvider>
        <Probe />
      </PwaInstallProvider>,
    );

    act(() => window.dispatchEvent(event));
    fireEvent.click(screen.getByRole('button', { name: 'prompt' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'unavailable' })).toBeInTheDocument(),
    );
  });
});

describe('iOS 판별', () => {
  it('iPhone Safari와 데스크톱 모드 iPad Safari만 홈 화면 추가 안내 대상으로 본다', () => {
    expect(isIosSafari('Mozilla/5.0 (iPhone) Version/18.0 Mobile Safari/604.1', 'iPhone', 5)).toBe(
      true,
    );
    expect(isIosSafari('Mozilla/5.0 (Macintosh) Version/18.0 Safari/605.1', 'MacIntel', 5)).toBe(
      true,
    );
    expect(isIosSafari('Mozilla/5.0 (iPhone) CriOS/120.0 Mobile Safari/604.1', 'iPhone', 5)).toBe(
      false,
    );
    expect(isIosSafari('Mozilla/5.0 (Windows NT 10.0) Safari/537.36', 'Win32', 0)).toBe(false);
  });
});
