/*
 * 뒤로 가기 (업무 S15P21E106-610)
 *
 * 앱 안에서 온 사람은 히스토리로 돌아가고, 새 탭이나 외부 링크로 바로 들어온 사람은
 * 화면이 정한 대체 경로로 간다. 둘을 가르는 것은 react-router가 매기는 `history.state.idx`다.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { useBack } from './useBack';

function BackButton({ fallbackTo }: { fallbackTo: string }) {
  const back = useBack(fallbackTo);

  return (
    <button type="button" onClick={back}>
      뒤로 가기
    </button>
  );
}

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

function renderAt(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path="/film" element={<p>필름</p>} />
        <Route path="/notifications" element={<BackButton fallbackTo="/my" />} />
        <Route path="/my" element={<p>MY</p>} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

afterEach(() => {
  window.history.replaceState(null, '');
});

describe('뒤로 가기', () => {
  it('앞에 이 앱의 항목이 없으면(새 탭 · 외부 링크) 대체 경로로 간다', () => {
    // react-router가 매긴 idx가 없거나 0이면 직접 들어온 것이다
    window.history.replaceState({ idx: 0 }, '');
    renderAt(['/notifications']);

    fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }));

    expect(screen.getByTestId('location').textContent).toBe('/my');
  });

  it('앱 안에서 이동해 왔으면 히스토리로 돌아간다', () => {
    window.history.replaceState({ idx: 1 }, '');
    renderAt(['/film', '/notifications']);

    fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }));

    expect(screen.getByTestId('location').textContent).toBe('/film');
  });
});
