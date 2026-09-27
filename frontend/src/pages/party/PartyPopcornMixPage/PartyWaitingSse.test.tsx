import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { notifyNotificationChange } from '../../../api/notificationStream';
import { renderWithRouter } from '../../../test/render';
import {
  ME,
  member,
  party,
  partyPopcorn,
  partyRecommendations,
  TEST_PARTY_ID,
} from '../../../test/party';
import PartyPopcornMixPage from './index';

vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');
  return {
    ...actual,
    getParty: vi.fn(),
    getPartyPopcorn: vi.fn(),
    getPartyRecommendations: vi.fn(),
  };
});

const waitingParty = () => party({ members: [member(ME, 'OWNER')] });

beforeEach(() => {
  vi.useFakeTimers();
  vi.mocked(getParty).mockReset().mockResolvedValue(waitingParty());
  vi.mocked(getPartyPopcorn).mockReset().mockResolvedValue(partyPopcorn());
  vi.mocked(getPartyRecommendations).mockReset().mockResolvedValue(partyRecommendations());
});

afterEach(() => vi.useRealTimers());

async function renderWaiting() {
  renderWithRouter(<PartyPopcornMixPage />, {
    route: `/parties/${TEST_PARTY_ID}/popcorn/mix`,
    path: '/parties/:partyId/popcorn/mix',
  });
  await act(async () => {});
  expect(screen.getByText('친구가 오면 함께 섞어요')).toBeInTheDocument();
}

async function signal() {
  await act(async () => {
    notifyNotificationChange();
    await vi.advanceTimersByTimeAsync(100);
  });
}

describe('1인 대기 SSE 갱신', () => {
  it('8초가 지나도 반복 조회하지 않고 수동 확인은 유지한다', async () => {
    await renderWaiting();
    await act(async () => vi.advanceTimersByTimeAsync(24_000));
    expect(getParty).toHaveBeenCalledTimes(1);
    expect(getPartyRecommendations).not.toHaveBeenCalled();
    await act(async () => fireEvent.click(screen.getByRole('button', { name: '참여 확인하기' })));
    expect(getParty).toHaveBeenCalledTimes(2);
  });

  it('초대 수락 신호로 섞기를 시작하고 이후 알림은 장면을 재시작하지 않는다', async () => {
    await renderWaiting();
    vi.mocked(getParty).mockResolvedValue(party());
    await signal();
    expect(screen.getByText('우리의 취향을 모아요')).toBeInTheDocument();
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);
    await signal();
    expect(getParty).toHaveBeenCalledTimes(2);
    await act(async () => vi.advanceTimersByTimeAsync(6200));
    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${TEST_PARTY_ID}/recommendations`,
    );
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);
  });

  it('재연결을 포함한 갱신 신호를 받고 연속 신호는 한 번의 조회로 모은다', async () => {
    await renderWaiting();
    await act(async () => {
      notifyNotificationChange();
      notifyNotificationChange();
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(getParty).toHaveBeenCalledTimes(2);
    expect(screen.getByText('친구가 오면 함께 섞어요')).toBeInTheDocument();
    vi.mocked(getParty).mockResolvedValue(party());
    await signal();
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);
  });

  it('이전 조회의 늦은 1인 응답이 새 2인 상태를 덮어쓰지 않는다', async () => {
    await renderWaiting();
    let resolveOld!: (value: ReturnType<typeof party>) => void;
    vi.mocked(getParty).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve;
      }),
    );
    await signal();
    vi.mocked(getParty).mockResolvedValue(party());
    await signal();
    await act(async () => resolveOld(waitingParty()));
    expect(screen.queryByText('친구가 오면 함께 섞어요')).not.toBeInTheDocument();
    expect(getPartyRecommendations).toHaveBeenCalledTimes(1);
  });
});
