import { request } from './client';

export type FeedbackInput = {
  category: 'GENERAL' | 'RECOMMENDATION';
  satisfaction: 'GOOD' | 'MIXED' | 'BAD' | null;
  comment: string | null;
  screenPath: string;
  movieId: number | null;
  recommendationType: string | null;
};

export function submitFeedback(input: FeedbackInput) {
  return request<{ id: number; createdAt: string }>('/feedback', {
    method: 'POST',
    body: input,
  });
}
