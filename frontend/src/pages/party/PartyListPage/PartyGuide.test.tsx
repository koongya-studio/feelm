import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PartyListPage from './index';
import { renderWithRouter } from '../../../test/render';
import { party, partyPopcorn, flavors } from '../../../test/party';
import {
  getClosedParties,
  getMyInvitations,
  getMyParties,
  getPartyPopcorn,
} from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import { PARTY_GUIDE, readGuideProgress } from '../../../utils/userGuides';

vi.mock('../../../contexts/authContext', () => ({
  useAuth: () => ({ user: { userId: 891 } }),
}));
vi.mock('../../../api/parties', async () => ({
  ...(await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties')),
  getMyParties: vi.fn(),
  getClosedParties: vi.fn(),
  getMyInvitations: vi.fn(),
  getPartyPopcorn: vi.fn(),
}));
vi.mock('../../../api/taste', async () => ({
  ...(await vi.importActual<typeof import('../../../api/taste')>('../../../api/taste')),
  getPopcornFlavors: vi.fn(),
}));

beforeEach(() => {
  localStorage.clear();
  vi.mocked(getMyParties).mockResolvedValue([]);
  vi.mocked(getClosedParties).mockResolvedValue([]);
  vi.mocked(getMyInvitations).mockResolvedValue([]);
  vi.mocked(getPartyPopcorn).mockResolvedValue(partyPopcorn());
  vi.mocked(getPopcornFlavors).mockResolvedValue(flavors());
});

it('파티가 없으면 만들기를 설명하고 이 화면에서 안내를 마친다', async () => {
  renderWithRouter(<PartyListPage />, { route: '/party' });

  expect(await screen.findByText('함께 영화 고르기')).toBeInTheDocument();
  expect(screen.getByRole('status', { name: '앱 사용 안내 1/1 단계' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '둘러보기' }));
  expect(screen.queryByText('함께 영화 고르기')).not.toBeInTheDocument();
});

it('참여 중인 파티가 있으면 입장을 순서대로 설명한다', async () => {
  vi.mocked(getMyParties).mockResolvedValue([party()]);
  const firstVisit = renderWithRouter(<PartyListPage />, { route: '/party' });

  expect(await screen.findByText('참여 중인 파티', { selector: 'strong' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  expect(screen.getByText('파티 안으로 들어가기')).toBeInTheDocument();
  expect(screen.getByRole('status', { name: '앱 사용 안내 2/2 단계' })).toBeInTheDocument();
  expect(readGuideProgress(891, PARTY_GUIDE)).toBe('open');

  firstVisit.unmount();
  renderWithRouter(<PartyListPage />, { route: '/party' });
  expect(await screen.findByRole('status', { name: '앱 사용 안내 2/2 단계' })).toBeInTheDocument();
});
