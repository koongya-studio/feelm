import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { submitFeedback } from '../../api/feedback';
import RecommendationFeedback from './index';

vi.mock('../../api/feedback', () => ({ submitFeedback: vi.fn() }));

beforeEach(() => {
  window.localStorage.clear();
  vi.mocked(submitFeedback).mockReset().mockResolvedValue({
    id: 1,
    createdAt: '2026-09-26T00:00:00Z',
  });
});

it('sends a recommendation rating with one tap and remembers it', async () => {
  render(<RecommendationFeedback storageKey="feedback:test-set" />);

  fireEvent.click(screen.getByRole('button', { name: '마음에 들어요' }));

  await waitFor(() => expect(submitFeedback).toHaveBeenCalledTimes(1));
  expect(submitFeedback).toHaveBeenCalledWith({
    category: 'RECOMMENDATION',
    satisfaction: 'GOOD',
    comment: null,
    screenPath: '/app/recommendations',
    movieId: null,
    recommendationType: 'TODAY',
  });
  expect(await screen.findByRole('status')).toHaveTextContent('의견 고마워요');
  expect(window.localStorage.getItem('feedback:test-set')).toBe('sent');
});

it('lets people send a comment without choosing a rating', async () => {
  render(<RecommendationFeedback storageKey="feedback:comment" />);

  fireEvent.click(screen.getByText('조금 더 알려주실래요?', { exact: false }));
  fireEvent.change(screen.getByLabelText('어떤 점이 좋거나 아쉬웠나요?'), {
    target: { value: '  새로운 영화가 좋아요  ' },
  });
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }));

  await waitFor(() =>
    expect(submitFeedback).toHaveBeenCalledWith(
      expect.objectContaining({ satisfaction: null, comment: '새로운 영화가 좋아요' }),
    ),
  );
});

it('waits to submit an expanded rating so a comment can be sent with it', async () => {
  render(<RecommendationFeedback storageKey="feedback:combined" />);

  fireEvent.click(screen.getByText('조금 더 알려주실래요?', { exact: false }));
  fireEvent.click(screen.getByRole('button', { name: '애매해요' }));
  expect(submitFeedback).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '애매해요' })).toHaveAttribute('aria-pressed', 'true');

  fireEvent.change(screen.getByLabelText('어떤 점이 좋거나 아쉬웠나요?'), {
    target: { value: '  설명이 조금 부족했어요  ' },
  });
  fireEvent.click(screen.getByRole('button', { name: '의견 보내기' }));

  await waitFor(() =>
    expect(submitFeedback).toHaveBeenCalledWith(
      expect.objectContaining({ satisfaction: 'MIXED', comment: '설명이 조금 부족했어요' }),
    ),
  );
});

it('records party feedback with the party result path and type', async () => {
  render(<RecommendationFeedback storageKey="feedback:party" partyId={9001} />);

  expect(screen.getByRole('region', { name: '이번 파티 추천 의견' })).toHaveTextContent(
    '이번 파티 추천은 어땠나요?',
  );
  fireEvent.click(screen.getByRole('button', { name: '마음에 들어요' }));

  await waitFor(() =>
    expect(submitFeedback).toHaveBeenCalledWith(
      expect.objectContaining({
        category: 'RECOMMENDATION',
        satisfaction: 'GOOD',
        screenPath: '/app/parties/9001/recommendations',
        recommendationType: 'PARTY',
      }),
    ),
  );
});

it('does not ask again for the same recommendation set', () => {
  window.localStorage.setItem('feedback:sent-set', 'sent');

  render(<RecommendationFeedback storageKey="feedback:sent-set" />);

  expect(screen.getByRole('status')).toHaveTextContent('의견 고마워요');
  expect(screen.queryByRole('button', { name: '마음에 들어요' })).not.toBeInTheDocument();
});

it('keeps the choices available when sending fails', async () => {
  vi.mocked(submitFeedback).mockRejectedValueOnce(new Error('network'));
  render(<RecommendationFeedback storageKey="feedback:failure" />);

  fireEvent.click(screen.getByRole('button', { name: '아쉬워요' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('의견을 보내지 못했어요');
  expect(screen.getByRole('button', { name: '아쉬워요' })).toBeEnabled();
  expect(window.localStorage.getItem('feedback:failure')).toBeNull();
});
