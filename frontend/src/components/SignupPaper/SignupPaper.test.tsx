/*
 * 가입 종이와 타자기 (업무 S15P21E106-718)
 *
 * 폰에서 입력 칸을 누르면 OS 키보드가 타자기와 겹쳐 키보드가 두 개로 보였다. 키보드가
 * 올라오면 타자기를 내리고, 닫히면 되돌린다.
 *
 * **자리는 남긴다.** 걷어내면 종이가 화면 아래로 늘어졌다가 키보드가 닫힐 때 되돌아온다.
 * 그래서 `data-hidden`으로 상태만 바꾸고 옮기는 일은 CSS가 맡는다.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import SignupPaper from './index';

type FakeViewport = {
  height: number;
  addEventListener: (type: string, listener: () => void) => void;
  removeEventListener: (type: string, listener: () => void) => void;
};

function stubViewport(height: number) {
  const listeners = new Set<() => void>();
  const viewport: FakeViewport = {
    height,
    addEventListener: (_type, listener) => void listeners.add(listener),
    removeEventListener: (_type, listener) => void listeners.delete(listener),
  };

  Object.defineProperty(window, 'visualViewport', { value: viewport, configurable: true });

  return (next: number) => {
    viewport.height = next;
    act(() => {
      listeners.forEach((listener) => listener());
    });
  };
}

/** 타자기. 장식이라 `aria-hidden`이므로 역할이 아니라 표시로 찾는다 */
const typewriter = () => document.querySelector('[data-hidden]');

afterEach(() => {
  Object.defineProperty(window, 'visualViewport', { value: undefined, configurable: true });
  vi.unstubAllGlobals();
});

describe('가입 종이의 타자기', () => {
  it('키보드가 올라오면 내려가고 닫히면 되돌아온다', () => {
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);

    render(
      <SignupPaper onSubmit={() => {}}>
        <input aria-label="닉네임" />
      </SignupPaper>,
    );

    expect(typewriter()).toHaveAttribute('data-hidden', 'false');

    resize(480);
    expect(typewriter()).toHaveAttribute('data-hidden', 'true');

    resize(812);
    expect(typewriter()).toHaveAttribute('data-hidden', 'false');
  });

  it('키보드가 뜨지 않는 환경에서는 그대로 보인다', () => {
    vi.stubGlobal('innerHeight', 900);
    stubViewport(900);

    render(
      <SignupPaper onSubmit={() => {}}>
        <input aria-label="닉네임" />
      </SignupPaper>,
    );

    expect(typewriter()).toHaveAttribute('data-hidden', 'false');
    // 입력 칸은 그대로 쓸 수 있다
    expect(screen.getByLabelText('닉네임')).toBeInTheDocument();
  });

  it('키보드가 덮은 만큼 아래 자리를 줄여 종이를 키보드 위에 세운다', () => {
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);

    const { container } = render(
      <SignupPaper onSubmit={() => {}}>
        <input aria-label="닉네임" />
      </SignupPaper>,
    );

    const page = container.firstElementChild as HTMLElement;

    expect(page.style.paddingBottom).toBe('');

    // 스크롤로는 못 올린다 — 내용이 짧으면 넘치는 높이가 없어 스크롤할 여지가 없다
    resize(476);
    expect(page.style.paddingBottom).toBe('336px');

    resize(812);
    expect(page.style.paddingBottom).toBe('');
  });

  it('내려가도 타자기를 지우지는 않는다 — 자리가 남아야 종이가 늘어지지 않는다', () => {
    vi.stubGlobal('innerHeight', 812);
    const resize = stubViewport(812);

    render(
      <SignupPaper onSubmit={() => {}}>
        <input aria-label="닉네임" />
      </SignupPaper>,
    );

    resize(480);
    expect(typewriter()).toBeInTheDocument();
  });
});
