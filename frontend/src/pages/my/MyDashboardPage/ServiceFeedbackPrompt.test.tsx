import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it } from 'vitest';
import ServiceFeedbackPrompt from './ServiceFeedbackPrompt';

beforeEach(() => window.localStorage.clear());

it('offers service feedback once and keeps the destination in MY', () => {
  const { unmount } = render(
    <MemoryRouter>
      <ServiceFeedbackPrompt userId={7} desktop={false} />
    </MemoryRouter>,
  );
  expect(screen.getByRole('link', { name: '서비스 의견 보내기' })).toHaveAttribute(
    'href',
    '/my/feedback?from=%2Fapp%2Fmy&category=GENERAL',
  );
  fireEvent.click(screen.getByRole('button', { name: '나중에' }));
  expect(screen.queryByRole('complementary', { name: '서비스 의견 안내' })).toBeNull();
  unmount();
  render(
    <MemoryRouter>
      <ServiceFeedbackPrompt userId={7} desktop={false} />
    </MemoryRouter>,
  );
  expect(screen.queryByRole('complementary', { name: '서비스 의견 안내' })).toBeNull();
});
