import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import FeedbackPage from './index';

const submit = vi.fn();
vi.mock('../../../api/feedback', () => ({
  submitFeedback: (...args: unknown[]) => submit(...args),
}));

beforeEach(() => submit.mockReset());

it('records the recommendation screen and prevents repeated submission', async () => {
  submit.mockResolvedValue({ id: 1, createdAt: '2026-09-25T00:00:00Z' });
  render(
    <MemoryRouter
      initialEntries={['/my/feedback?from=%2Fapp%2Frecommendations&category=RECOMMENDATION']}
    >
      <Routes>
        <Route path="/my/feedback" element={<FeedbackPage />} />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByLabelText('마음에 들어요'));
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }));
  await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
  expect(submit).toHaveBeenCalledWith(
    expect.objectContaining({
      category: 'RECOMMENDATION',
      satisfaction: 'GOOD',
      screenPath: '/app/recommendations',
    }),
  );
  expect(await screen.findByText('의견 감사합니다!')).toBeInTheDocument();
});

it('requires satisfaction or a written opinion', () => {
  render(
    <MemoryRouter initialEntries={['/my/feedback']}>
      <Routes>
        <Route path="/my/feedback" element={<FeedbackPage />} />
      </Routes>
    </MemoryRouter>,
  );
  expect(screen.getByRole('button', { name: '의견 보내기' })).toBeDisabled();
  fireEvent.change(
    screen.getByPlaceholderText('좋았던 점, 불편했던 점, 바라는 점을 자유롭게 적어주세요.'),
    { target: { value: '검색 화면에서 버튼이 눌리지 않았어요.' } },
  );
  expect(screen.getByRole('button', { name: '의견 보내기' })).toBeEnabled();
});

it('asks for service feedback while keeping the source path for submission', async () => {
  submit.mockResolvedValue({ id: 3, createdAt: '2026-09-25T00:00:00Z' });
  render(
    <MemoryRouter initialEntries={['/my/feedback?from=%2Fapp%2Ffilm']}>
      <Routes>
        <Route path="/my/feedback" element={<FeedbackPage />} />
      </Routes>
    </MemoryRouter>,
  );
  expect(screen.getByText('Feelm 서비스에 대한 의견으로 기록돼요')).toBeInTheDocument();
  expect(screen.getByText('Feelm은 어땠나요?')).toBeInTheDocument();
  expect(screen.queryByText('/app/film')).not.toBeInTheDocument();
  fireEvent.click(screen.getByLabelText('마음에 들어요'));
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }));
  await waitFor(() =>
    expect(submit).toHaveBeenCalledWith(expect.objectContaining({ screenPath: '/app/film' })),
  );
});

it('includes the movie id when sent from a movie detail screen', async () => {
  submit.mockResolvedValue({ id: 2, createdAt: '2026-09-25T00:00:00Z' });
  render(
    <MemoryRouter initialEntries={['/my/feedback?from=%2Fapp%2Fmovies%2F42']}>
      <Routes>
        <Route path="/my/feedback" element={<FeedbackPage />} />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.change(
    screen.getByPlaceholderText('좋았던 점, 불편했던 점, 바라는 점을 자유롭게 적어주세요.'),
    { target: { value: '영화 상세 오류' } },
  );
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }));
  await waitFor(() =>
    expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({
        screenPath: '/app/movies/42',
        movieId: 42,
      }),
    ),
  );
});

it('uses recommendation wording and category when opened from the recommendation screen', async () => {
  submit.mockResolvedValue({ id: 4, createdAt: '2026-09-25T00:00:00Z' });
  render(
    <MemoryRouter initialEntries={['/my/feedback?from=%2Fapp%2Frecommendations&category=GENERAL']}>
      <Routes>
        <Route path="/my/feedback" element={<FeedbackPage />} />
      </Routes>
    </MemoryRouter>,
  );
  expect(screen.getByText('나를 위한 영화 추천은 어땠나요?')).toBeInTheDocument();
  expect(screen.getByLabelText('아쉬워요')).toBeInTheDocument();
  expect(screen.getByPlaceholderText('어떤 점이 마음에 들거나 아쉬웠나요?')).toBeInTheDocument();
  fireEvent.click(screen.getByLabelText('아쉬워요'));
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }));
  await waitFor(() =>
    expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({ category: 'RECOMMENDATION', satisfaction: 'BAD' }),
    ),
  );
});
