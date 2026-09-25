/*
 * 가입 종이와 타자기 (업무 S15P21E106-718 · -882)
 *
 * 폰에서 입력 칸을 누르면 OS 키보드가 타자기와 겹쳐 키보드가 두 개로 보였다(718). 키만
 * 감췄더니 기종마다 롤러와 키보드 사이가 떴다(882) — 안드로이드는 키보드를 못 알아챘고, 홈
 * 막대가 있는 아이폰은 아래 안전 영역만큼 떴다.
 *
 * 고정할 것
 * - 폰에서 키보드가 올라오면 롤러와 얇은 윗판만 남기고(`data-compact`) 닫히면 전체로 돌아온다
 * - 웹(데스크톱 셸)은 키보드 상태와 무관하게 전체 타자기다
 * - 스크롤 영역의 아래 끝을 **보이는 영역의 아래 끝에** 맞춘다. 키보드 높이가 아니라 두 위치의
 *   차이라 안전 영역 · 안드로이드의 줄어든 화면이 맞는다
 * - 타자기를 지우지 않는다 — 자리가 남아야 종이가 늘어지지 않는다
 * - 초점이 간 줄(입력 칸 + 오류 문구)을 보이는 곳으로 올린다
 *
 * jsdom에는 레이아웃이 없어 스크롤 영역의 위치를 직접 준다. 실제 겹침은 기기 폭 브라우저로 본다
 * (MR 본문의 수동 검증).
 */
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import SignupPaper, { SignupPaperLine } from './index';
import { resetViewport, setViewportWidth } from '../../test/viewport';

type Listener = () => void;

function stubViewport(height: number) {
  const listeners = new Set<Listener>();
  const viewport = {
    width: 390,
    height,
    offsetTop: 0,
    addEventListener: (type: string, listener: Listener) => {
      if (type === 'resize') listeners.add(listener);
    },
    removeEventListener: (_type: string, listener: Listener) => void listeners.delete(listener),
  };

  Object.defineProperty(window, 'visualViewport', { value: viewport, configurable: true });

  return (next: number, { layout }: { layout?: number } = {}) => {
    viewport.height = next;
    if (layout !== undefined) vi.stubGlobal('innerHeight', layout);
    act(() => listeners.forEach((listener) => listener()));
  };
}

/** 타자기. 장식이라 `aria-hidden`이므로 역할이 아니라 표시로 찾는다 */
const typewriter = () => document.querySelector('[aria-hidden="true"][data-compact]');

/** 스크롤 영역의 아래 끝(레이아웃 좌표)을 정한다. 바깥 여백으로 올린 만큼 올라간다 */
function placePage(page: HTMLElement, bottom: number) {
  page.getBoundingClientRect = () => {
    const lifted = bottom - (parseFloat(page.style.marginBottom) || 0);

    return { top: 0, bottom: lifted, left: 0, right: 390, width: 390, height: lifted } as DOMRect;
  };
}

function renderPaper() {
  const result = render(
    <SignupPaper onSubmit={() => {}}>
      <SignupPaperLine line={1}>
        <input aria-label="닉네임" />
        <p>이미 쓰고 있는 닉네임이에요</p>
      </SignupPaperLine>
    </SignupPaper>,
  );

  return { ...result, page: result.container.firstElementChild as HTMLElement };
}

const focusNickname = () => act(() => screen.getByLabelText('닉네임').focus());

beforeEach(() => {
  setViewportWidth(390);
});

afterEach(() => {
  Object.defineProperty(window, 'visualViewport', { value: undefined, configurable: true });
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  resetViewport();
});

describe('가입 종이의 타자기', () => {
  it('폰에서 키보드가 올라오면 윗부분만 남고 닫히면 전체로 돌아온다', () => {
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);
    renderPaper();

    expect(typewriter()).toHaveAttribute('data-compact', 'false');

    focusNickname();
    resize(476);
    expect(typewriter()).toHaveAttribute('data-compact', 'true');

    resize(812);
    expect(typewriter()).toHaveAttribute('data-compact', 'false');
  });

  it('웹(데스크톱 셸)에서는 키보드가 올라와도 전체 타자기다', () => {
    setViewportWidth(1280);
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);
    const { page } = renderPaper();

    focusNickname();
    resize(476);

    expect(typewriter()).toHaveAttribute('data-compact', 'false');
    expect(page.style.marginBottom).toBe('');
  });

  it('키보드가 뜨지 않는 환경에서는 그대로 보인다', () => {
    vi.stubGlobal('innerHeight', 900);
    stubViewport(900);
    renderPaper();

    focusNickname();

    expect(typewriter()).toHaveAttribute('data-compact', 'false');
  });

  it('홈 막대가 있는 아이폰 — 안전 영역만큼 덜 올려 롤러가 키보드에 붙는다', () => {
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);
    const { page } = renderPaper();
    // 레이아웃이 아래 안전 영역 34px을 비워 스크롤 영역은 778에서 끝난다
    placePage(page, 778);

    focusNickname();
    resize(476);

    // 키보드 높이(336)만큼 올리면 34px이 떴다. 보이는 아래 끝(476)까지의 차이만큼 올린다
    expect(page.style.marginBottom).toBe('302px');
    expect(page.getBoundingClientRect().bottom).toBe(476);

    resize(812);
    expect(page.style.marginBottom).toBe('');
  });

  it('안드로이드 — 화면이 이미 줄었으면 더 올리지 않는다', () => {
    vi.stubGlobal('innerHeight', 780);
    const resize = stubViewport(780);
    const { page } = renderPaper();

    focusNickname();
    // `resizes-content`라 레이아웃도 470으로 줄고 스크롤 영역도 거기서 끝난다
    placePage(page, 470);
    resize(470, { layout: 470 });

    expect(typewriter()).toHaveAttribute('data-compact', 'true');
    expect(page.style.marginBottom).toBe('');
  });

  it('윗부분만 남아도 타자기를 지우지는 않는다 — 자리가 남아야 종이가 늘어지지 않는다', () => {
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);
    renderPaper();

    focusNickname();
    resize(476);

    expect(typewriter()).toBeInTheDocument();
  });

  it('키보드가 올라오면 초점이 간 줄을 오류 문구까지 보이는 곳으로 올린다', async () => {
    // jsdom에는 없어 이 시험 동안만 둔다
    const scrolled: Element[] = [];
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      value(this: Element) {
        scrolled.push(this);
      },
    });
    onTestFinished(() => {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    });
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);
    renderPaper();

    focusNickname();
    resize(476);

    const line = screen.getByLabelText('닉네임').closest('[data-signup-line]');

    await waitFor(() => expect(scrolled).toContain(line));
    // 칸만이 아니라 오류 문구를 품은 줄 전체다
    expect(line).toContainElement(screen.getByText('이미 쓰고 있는 닉네임이에요'));
  });
});
