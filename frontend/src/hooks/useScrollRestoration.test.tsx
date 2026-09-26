/*
 * 뒤로 가기로 돌아오면 보던 자리로 (업무 S15P21E106-520)
 *
 * 하단 내비 화면은 `AppLayout`의 본문이 스크롤한다. 여기서는 그 본문과 같은 자리에 훅을 건
 * 껍데기를 세우고 경로 단위로 확인한다 — 3.1에서 내린 뒤 전체화면에 다녀오기(껍데기가 사라졌다
 * 다시 생긴다), 같은 껍데기 안의 4.1에 다녀오기, 탭으로 새로 들어오기.
 *
 * jsdom은 레이아웃이 없어 `scrollTop`이 잘리지 않고 적은 값 그대로 남는다. 내용이 늦게 차는
 * 경우는 `scrollTop`을 직접 잘라 흉내 낸다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { useRef } from 'react';
import { MemoryRouter, Outlet, Route, Routes, useNavigate } from 'react-router';
import { resetScrollPositions, useScrollRestoration } from './useScrollRestoration';

function Shell() {
  const bodyRef = useRef<HTMLDivElement>(null);

  useScrollRestoration(bodyRef);

  return (
    <div ref={bodyRef} data-testid="body">
      <Outlet />
    </div>
  );
}

function Nav() {
  const navigate = useNavigate();

  return (
    <>
      <button type="button" onClick={() => void navigate('/film')}>
        필름 탭
      </button>
      <button type="button" onClick={() => void navigate('/my')}>
        MY 탭
      </button>
      <button type="button" onClick={() => void navigate('/search')}>
        검색
      </button>
      <button type="button" onClick={() => void navigate('/frames/1')}>
        프레임
      </button>
      <button type="button" onClick={() => void navigate(-1)}>
        뒤로
      </button>
      <button
        type="button"
        onClick={() =>
          void navigate('/my?tab=recommendations', {
            replace: true,
            state: { preserveScroll: true },
          })
        }
      >
        추천 기록
      </button>
    </>
  );
}

function renderApp() {
  return render(
    <MemoryRouter initialEntries={['/film']}>
      <Nav />
      <Routes>
        <Route element={<Shell />}>
          <Route path="/film" element={<p>필름</p>} />
          <Route path="/my" element={<p>MY</p>} />
          <Route path="/search" element={<p>검색 화면</p>} />
        </Route>
        {/* 전체화면. 껍데기 밖이라 가면 본문이 사라진다 */}
        <Route path="/frames/:id" element={<p>프레임 상세</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

const body = () => screen.getByTestId('body');

/** 사용자가 본문을 내린 것처럼 값을 주고 `scroll`을 낸다 */
function scrollBodyTo(top: number) {
  body().scrollTop = top;
  fireEvent.scroll(body());
}

function go(name: string) {
  fireEvent.click(screen.getByRole('button', { name }));
}

beforeEach(() => {
  resetScrollPositions();
});

describe('useScrollRestoration', () => {
  it('MY 내부 탭은 위치를 유지하고 상세에서 뒤로 와도 그 위치를 복원한다', async () => {
    renderApp();
    go('MY 탭');
    scrollBodyTo(540);
    go('추천 기록');
    expect(body().scrollTop).toBe(540);
    go('프레임');
    await screen.findByText('프레임 상세');
    go('뒤로');
    expect(await screen.findByText('MY')).toBeInTheDocument();
    expect(body().scrollTop).toBe(540);
  });

  it('스크롤 유지 표시가 있어도 다른 경로로 이동하면 맨 위에서 시작한다', () => {
    renderApp();
    scrollBodyTo(540);
    go('추천 기록');
    expect(body().scrollTop).toBe(0);
  });

  it('전체화면에 다녀와 뒤로 오면 보던 자리로 돌아온다', async () => {
    renderApp();
    scrollBodyTo(640);

    go('프레임');
    expect(await screen.findByText('프레임 상세')).toBeInTheDocument();
    expect(screen.queryByTestId('body')).toBeNull();

    go('뒤로');
    expect(await screen.findByText('필름')).toBeInTheDocument();
    expect(body().scrollTop).toBe(640);
  });

  it('같은 껍데기 안의 4.1 검색에 다녀와도 돌아온다. 검색은 맨 위에서 시작한다', async () => {
    renderApp();
    scrollBodyTo(480);

    go('검색');
    expect(await screen.findByText('검색 화면')).toBeInTheDocument();
    // 본문이 그대로 남으므로 되돌리지 않으면 480이 검색에 묻어 온다
    expect(body().scrollTop).toBe(0);

    go('뒤로');
    expect(await screen.findByText('필름')).toBeInTheDocument();
    expect(body().scrollTop).toBe(480);
  });

  it('하단 탭으로 새로 들어온 화면은 맨 위에서 시작한다', async () => {
    renderApp();
    scrollBodyTo(300);

    go('MY 탭');
    expect(await screen.findByText('MY')).toBeInTheDocument();
    expect(body().scrollTop).toBe(0);

    // 탭으로 필름에 다시 오는 것은 새 항목이다. 전에 보던 자리를 쓰지 않는다
    go('필름 탭');
    expect(await screen.findByText('필름')).toBeInTheDocument();
    expect(body().scrollTop).toBe(0);
  });

  it('돌아온 화면이 아직 짧으면 목표에 닿을 때까지 다시 맞춘다', async () => {
    renderApp();
    scrollBodyTo(900);
    go('프레임');
    await screen.findByText('프레임 상세');

    // 데이터를 다시 받는 동안 본문 높이가 200까지만 된다고 흉내 낸다
    let maxTop = 200;
    const frames: FrameRequestCallback[] = [];
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    let stored = 0;
    Object.defineProperty(HTMLElement.prototype, 'scrollTop', {
      configurable: true,
      get: () => stored,
      set: (value: number) => {
        stored = Math.min(value, maxTop);
      },
    });

    try {
      go('뒤로');
      await screen.findByText('필름');
      expect(body().scrollTop).toBe(200);

      // 내용이 다 차면 다음 프레임에 목표에 닿는다
      maxTop = 2000;
      act(() => frames.shift()?.(0));
      expect(body().scrollTop).toBe(900);
      expect(frames).toHaveLength(0);
    } finally {
      delete (HTMLElement.prototype as { scrollTop?: number }).scrollTop;
      vi.restoreAllMocks();
    }
  });

  it('되돌리는 중에 사용자가 스크롤을 건드리면 멈춘다', async () => {
    renderApp();
    scrollBodyTo(900);
    go('프레임');
    await screen.findByText('프레임 상세');

    const frames: FrameRequestCallback[] = [];
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      frames.push(callback);
      return frames.length;
    });
    const cancel = vi.spyOn(window, 'cancelAnimationFrame');
    let stored = 0;
    Object.defineProperty(HTMLElement.prototype, 'scrollTop', {
      configurable: true,
      get: () => stored,
      set: (value: number) => {
        stored = Math.min(value, 200);
      },
    });

    try {
      go('뒤로');
      await screen.findByText('필름');

      fireEvent.touchStart(body());
      expect(cancel).toHaveBeenCalled();

      // 손으로 옮긴 자리가 이 항목의 새 자리다
      scrollBodyTo(120);
      go('프레임');
      await screen.findByText('프레임 상세');
      go('뒤로');
      await screen.findByText('필름');
      expect(body().scrollTop).toBe(120);
    } finally {
      delete (HTMLElement.prototype as { scrollTop?: number }).scrollTop;
      vi.restoreAllMocks();
    }
  });
});
