import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import FilmReel from './index';

function renderReel(onClick = vi.fn()) {
  render(
    <FilmReel frameWidth={110} frameHeight={165} label="취향 필름 프레임">
      <button type="button" onClick={onClick}>
        영화 상세 보기
      </button>
    </FilmReel>,
  );

  const track = screen.getByRole('group', { name: '취향 필름 프레임' });
  const setPointerCapture = vi.fn();
  const releasePointerCapture = vi.fn();

  Object.assign(track, {
    scrollLeft: 40,
    setPointerCapture,
    hasPointerCapture: vi.fn(() => true),
    releasePointerCapture,
  });

  return { track, onClick, setPointerCapture, releasePointerCapture };
}

describe('FilmReel 마우스 드래그', () => {
  it('가로 릴을 잡아 끌면 이동한 거리만큼 스크롤하고 프레임 클릭은 실행하지 않는다', () => {
    const { track, onClick, setPointerCapture, releasePointerCapture } = renderReel();

    fireEvent.pointerDown(track, {
      pointerId: 7,
      pointerType: 'mouse',
      button: 0,
      clientX: 300,
    });
    fireEvent.pointerMove(track, { pointerId: 7, pointerType: 'mouse', clientX: 100 });
    fireEvent.pointerUp(track, { pointerId: 7, pointerType: 'mouse' });
    fireEvent.click(screen.getByRole('button', { name: '영화 상세 보기' }));

    expect(setPointerCapture).toHaveBeenCalledWith(7);
    expect(track.scrollLeft).toBe(240);
    expect(releasePointerCapture).toHaveBeenCalledWith(7);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('끌지 않고 프레임을 누르면 원래 클릭 동작을 유지한다', () => {
    const { track, onClick, setPointerCapture } = renderReel();

    fireEvent.pointerDown(track, {
      pointerId: 8,
      pointerType: 'mouse',
      button: 0,
      clientX: 200,
    });
    fireEvent.pointerUp(track, { pointerId: 8, pointerType: 'mouse' });
    fireEvent.click(screen.getByRole('button', { name: '영화 상세 보기' }));

    expect(track.scrollLeft).toBe(40);
    expect(setPointerCapture).not.toHaveBeenCalled();
    expect(onClick).toHaveBeenCalledOnce();
  });
});
