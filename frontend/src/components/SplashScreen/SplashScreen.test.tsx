/*
 * 스플래시 — 걷히는 조건 테스트 (업무 S15P21E106-331)
 *
 * 이 컴포넌트가 하는 판단은 **언제 사라지는가** 하나다. 그런데 그 조건이 셋
 * (사진 · 서체 · 최소 시간)이고 전부 비동기라, 하나만 빠져도 화면이 영구히
 * 남거나 반대로 깜빡이고 지나간다. 둘 다 눈으로는 알아채기 어려운 회귀다.
 *
 * jsdom은 이미지를 실제로 받지 않으므로 `load`/`error`를 직접 발생시킨다.
 * 시간은 가짜 타이머로 밀어 1.2초를 실제로 기다리지 않는다.
 *
 * **서체는 여기서 직접 붙잡아야 한다.** `src/test/setup.ts`의 `document.fonts`
 * 스텁은 이미 로드된 것으로 답하므로(`Promise.resolve()`) 서체 조건이 언제나
 * 먼저 참이 되고, 그 조건을 지워도 테스트가 통과한다. 실제로 그렇게 확인했다.
 * 그래서 이 조건을 검증하는 두 테스트만 `ready`를 아직 끝나지 않은 Promise로
 * 바꿔 끼운다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import SplashScreen from './index';

/** 서체 준비 Promise(마이크로태스크)와 타이머를 함께 흘린다 */
async function advance(ms: number) {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
}

async function flush() {
  await act(async () => {});
}

function photo() {
  return screen.getByAltText('Feelm — YOUR TASTE ON FILM');
}

function splash() {
  return screen.getByTestId('splash');
}

/** 테스트 전체의 기본값(setup.ts의 스텁). 각 테스트 뒤에 이 값으로 되돌린다 */
const defaultFonts = document.fonts;

/**
 * 서체 로드를 아직 끝나지 않은 상태로 붙잡는다. 끝내는 시점을 테스트가 정해야
 * 서체 조건이 게이트로 동작하는지 볼 수 있다.
 */
function holdFonts() {
  let finish: (() => void) | undefined;
  let fail: (() => void) | undefined;
  const ready = new Promise<void>((resolve, reject) => {
    finish = () => resolve();
    fail = () => reject(new Error('서체 로드 실패'));
  });

  Object.defineProperty(document, 'fonts', { configurable: true, value: { ready } });

  return {
    finish: () => finish?.(),
    fail: () => fail?.(),
  };
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  Object.defineProperty(document, 'fonts', { configurable: true, value: defaultFonts });
});

describe('SplashScreen', () => {
  it('처음에는 사진으로 화면을 덮는다', async () => {
    render(<SplashScreen />);
    await flush();

    expect(splash()).toHaveAttribute('data-leaving', 'false');
    // 시안 2.8이 정한 사진 한 장이다. 로고를 위에 겹쳐 올리지 않는다
    expect(photo()).toBeInTheDocument();
    expect(splash().querySelectorAll('img')).toHaveLength(1);
  });

  it('준비가 끝나도 최소 표시 시간 전에는 걷히지 않는다', async () => {
    render(<SplashScreen />);
    await flush();

    // 사진과 서체가 모두 준비된 상태(두 번째 방문이면 캐시에서 즉시 끝난다)
    fireEvent.load(photo());
    await flush();
    await advance(1199);

    expect(splash()).toHaveAttribute('data-leaving', 'false');
  });

  it('최소 시간이 지나고 준비도 끝나면 걷힌 뒤 DOM에서 빠진다', async () => {
    render(<SplashScreen />);
    await flush();

    fireEvent.load(photo());
    await advance(1200);

    // 먼저 페이드가 시작되고
    expect(splash()).toHaveAttribute('data-leaving', 'true');

    // 페이드가 끝나면 남지 않는다. 남으면 탭이 막힌다
    await advance(1000);
    expect(screen.queryByTestId('splash')).not.toBeInTheDocument();
  });

  it('최소 시간이 지났어도 사진이 아직이면 기다린다', async () => {
    render(<SplashScreen />);
    await flush();

    await advance(1200);
    expect(splash()).toHaveAttribute('data-leaving', 'false');

    fireEvent.load(photo());
    await flush();
    expect(splash()).toHaveAttribute('data-leaving', 'true');
  });

  it('서체가 아직 로드되지 않으면 사진과 최소 시간이 끝나도 기다린다', async () => {
    const fonts = holdFonts();

    render(<SplashScreen />);
    await flush();

    fireEvent.load(photo());
    await advance(1200);

    // 사진과 시간은 끝났다. 남은 것은 서체뿐이다
    expect(splash()).toHaveAttribute('data-leaving', 'false');

    fonts.finish();
    await flush();

    expect(splash()).toHaveAttribute('data-leaving', 'true');
  });

  it('서체 로드가 실패해도 앱 진입을 막지 않는다', async () => {
    const fonts = holdFonts();

    render(<SplashScreen />);
    await flush();

    fireEvent.load(photo());
    await advance(1200);
    expect(splash()).toHaveAttribute('data-leaving', 'false');

    // 서체는 fallback으로 그려진다. 이것 때문에 갇히면 안 된다
    fonts.fail();
    await flush();
    await advance(1000);

    expect(screen.queryByTestId('splash')).not.toBeInTheDocument();
  });

  it('사진 로드가 실패해도 앱 진입을 막지 않는다', async () => {
    render(<SplashScreen />);
    await flush();

    fireEvent.error(photo());
    await advance(1200);
    await advance(1000);

    expect(screen.queryByTestId('splash')).not.toBeInTheDocument();
  });
});
