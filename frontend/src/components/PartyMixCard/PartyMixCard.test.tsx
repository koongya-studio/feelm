import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import PartyMixCard, { PARTY_POPULARITY_NOTICE } from './index';
import { flavors, memberPopcorn, ME } from '../../test/party';
import type { PartyPopcornSnapshot } from '../../types/party';
import { getPopcornFlavors } from '../../api/taste';
vi.mock('../../api/taste', () => ({ getPopcornFlavors: vi.fn() }));
beforeEach(() => {
  vi.mocked(getPopcornFlavors).mockResolvedValue(flavors());
});
function mix(counts = [3, 2]): PartyPopcornSnapshot {
  const member = memberPopcorn(ME, counts);
  return {
    recommendationSetId: 1,
    exposedAt: '2026-09-27T00:00:00Z',
    recommendationMode: 'PERSONALIZED',
    members: [member],
    totalCount: member.totalCount,
    flavors: member.flavors,
  };
}
it('shows summary and toggles all eight flavors using the whole header', async () => {
  render(<PartyMixCard mix={mix()} />);
  expect(await screen.findByText('맛1 · 맛2')).toBeInTheDocument();
  const toggle = screen.getByRole('button', { name: /함께 섞은 팝콘/ });
  expect(toggle).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(toggle);
  expect(toggle).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByText('맛8')).toBeInTheDocument();
  expect(screen.queryByText('팝콘 크게 보기')).not.toBeInTheDocument();
  fireEvent.click(toggle);
  expect(screen.queryByText('맛8')).not.toBeInTheDocument();
});
it('keeps empty mix without expansion and only shows popularity notice from mode', async () => {
  const empty = mix([]);
  const view = render(<PartyMixCard mix={empty} />);
  expect(await screen.findByText('아직 담긴 팝콘이 없어요')).toBeInTheDocument();
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
  expect(screen.queryByText(PARTY_POPULARITY_NOTICE)).not.toBeInTheDocument();
  view.rerender(<PartyMixCard mix={{ ...empty, recommendationMode: 'POPULARITY' }} />);
  expect(screen.getByText(PARTY_POPULARITY_NOTICE)).toBeInTheDocument();
});
