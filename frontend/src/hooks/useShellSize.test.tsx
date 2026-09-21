/*
 * 셸 단계 판정 (업무 -782).
 *
 * 기준점은 1024px과 1280px이다. 경계값에서 갈리는 방향을 고정한다 — `min-width: 1024px`은
 * 1024를 포함하므로 1023이 모바일, 1024가 데스크톱이다.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { useShellSize } from './useShellSize';
import { resetViewport, setViewportWidth } from '../test/viewport';

function Probe() {
  return <div data-testid="shell">{useShellSize()}</div>;
}

/* 한 테스트에서 경계 양쪽을 잰다. 앞의 그림을 치우지 않으면 같은 `testid`가 둘 남는다 */
function shellAt(width: number) {
  cleanup();
  setViewportWidth(width);
  render(<Probe />);

  return screen.getByTestId('shell').textContent;
}

afterEach(() => {
  resetViewport();
});

describe('useShellSize', () => {
  it('412px 이하는 모바일이다', () => {
    expect(shellAt(412)).toBe('mobile');
  });

  it('768px 태블릿도 아직 모바일 셸이다', () => {
    expect(shellAt(768)).toBe('mobile');
  });

  it('1023px까지는 모바일이고 1024px부터 데스크톱이다', () => {
    expect(shellAt(1023)).toBe('mobile');
    expect(shellAt(1024)).toBe('desktop');
  });

  it('1279px까지는 한 단이고 1280px부터 보조 패널이 열린다', () => {
    expect(shellAt(1279)).toBe('desktop');
    expect(shellAt(1280)).toBe('wide');
  });

  it('1440px도 보조 패널이 열린 단계다', () => {
    expect(shellAt(1440)).toBe('wide');
  });

  it('폭을 알 수 없으면 좁은 쪽으로 본다', () => {
    render(<Probe />);

    expect(screen.getByTestId('shell').textContent).toBe('mobile');
  });
});
