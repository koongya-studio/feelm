import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import WatchConfirmPage from './index';
import { confirmWatch, findWatchRecord } from '../../../api/watch';
import { getOtts } from '../../../api/otts';

vi.mock('../../../api/watch', () => ({ confirmWatch: vi.fn(), findWatchRecord: vi.fn() }));
vi.mock('../../../api/otts', () => ({ getOtts: vi.fn() }));

beforeEach(() => {
  vi.mocked(findWatchRecord)
    .mockReset()
    .mockResolvedValue({
      watchRecordId: 12,
      movie: { movieId: 550, title: '파이트 클럽', posterUrl: null },
      status: 'LINK_CLICKED',
      ott: null,
      clickedAt: new Date().toISOString(),
      confirmDueAt: null,
      expiresAt: null,
      watchedAt: null,
      ratingId: null,
    });
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(confirmWatch).mockReset().mockResolvedValue({
    watchRecordId: 12,
    status: 'WATCHED_UNRATED',
    watchedAt: null,
  });
});

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/watch-records/12/confirm']}>
      <Routes>
        <Route path="/watch-records/:watchRecordId/confirm" element={<WatchConfirmPage />} />
        <Route path="/notifications" element={<p>알림 목록</p>} />
        <Route path="/movies/:movieId/rate" element={<p>영화 평가 화면</p>} />
        <Route path="/film" element={<p>필름 화면</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('감상 확인 선택지', () => {
  it('아직 못 봤어요는 확인 API 없이 알림 목록으로 돌아간다', async () => {
    renderPage();
    const later = await screen.findByRole('button', { name: '아직 못 봤어요' });
    expect(later).toHaveAttribute('data-variant', 'secondary');
    expect(screen.queryByRole('button', { name: '뒤로 가기' })).not.toBeInTheDocument();
    fireEvent.click(later);
    expect(await screen.findByText('알림 목록')).toBeInTheDocument();
    expect(confirmWatch).not.toHaveBeenCalled();
  });

  it('봤어요는 감상 완료를 보내고 평가 화면으로 이동한다', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '봤어요' }));
    expect(confirmWatch).toHaveBeenCalledWith(12, true);
    expect(await screen.findByText('영화 평가 화면')).toBeInTheDocument();
  });

  it('보지 않을래요는 대기 종료를 보내고 필름으로 이동한다', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '보지 않을래요' }));
    expect(confirmWatch).toHaveBeenCalledWith(12, false);
    expect(await screen.findByText('필름 화면')).toBeInTheDocument();
  });
});
