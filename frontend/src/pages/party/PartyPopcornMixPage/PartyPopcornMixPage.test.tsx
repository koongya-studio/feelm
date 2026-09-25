/*
 * 5.9 팝콘 모으기 · 섞기 (업무 S15P21E106-462 · -773)
 *
 * 세 가지를 고정한다.
 *
 * **누르지 않아도 모으기에서 섞기로 이어진다**(사용자 결정 2026-09-21, 업무 -773). 한때
 * 5.4가 따로 된 화면이라 장면이 끊겼고, 5.9에는 `팝콘 섞기` 버튼이 있어 누를 곳이 많았다.
 * 이제 파티원 통이 기계 위로 모이고, 그대로 붓고 섞고 담긴다.
 *
 * **담긴 뒤에 5.5로 간다.** 추천(API-058)은 화면을 열자마자 받아 두고, 받지 못하면 장면을
 * 끝까지 보여 준 뒤 5.5로 보내지 않고 이 화면에서 알린다 — 섞기 실패가 아니라 추천 실패로.
 *
 * **추천만 다시 묻는다.** 팝콘은 이미 섞였으므로 다시 시도는 장면을 되풀이하지 않는다.
 *
 * `[!]` 이 파일은 **테스트 자체의 제한 시간**을 늘려 둔다(업무 -617). 모으기 · 섞기 연출이 약
 * 6초인데 Vitest 기본 제한이 5초라, 전체 실행처럼 러너가 붐비면 그 안에 끝나지 않는다.
 * `findBy` · `waitFor`에 준 `timeout`은 그 대기 헬퍼의 제한일 뿐 테스트의 제한을 늘리지 않는다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { ApiError } from '../../../api/apiError';
import PartyPopcornMixPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import {
  ME,
  member,
  memberPopcorn,
  party,
  partyPopcorn,
  partyRecommendations,
  TEST_PARTY_ID,
} from '../../../test/party';

/*
 * 연출을 끝까지 기다리는 테스트가 있어 이 파일의 제한 시간을 올린다. `it`마다 제한을 붙이면
 * Prettier가 본문을 모두 들여써 변경이 커지므로 파일 단위로 한 번 준다. 제한 시간은 상한이라
 * 연출을 기다리지 않는 테스트가 느려지지는 않는다.
 */
vi.setConfig({ testTimeout: 12000 });

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

beforeEach(() => {
  vi.mocked(getParty).mockReset().mockResolvedValue(party());
  vi.mocked(getPartyPopcorn).mockReset().mockResolvedValue(partyPopcorn());
  vi.mocked(getPartyRecommendations).mockReset().mockResolvedValue(partyRecommendations());
});

function renderMix() {
  return renderWithRouter(<PartyPopcornMixPage />, {
    route: `/parties/${TEST_PARTY_ID}/popcorn/mix`,
    path: '/parties/:partyId/popcorn/mix',
  });
}

describe('5.9 팝콘 모으기 · 섞기', () => {
  it('1명 참여와 초대 대기 3명을 각각 팝콘으로 표시한다', async () => {
    const invitees = [2048, 3072, 4096].map((userId, index) => ({
      invitationId: index + 1,
      invitee: { userId, nickname: `친구${index + 1}` },
      createdAt: '2026-09-25T00:00:00Z',
    }));
    vi.mocked(getParty).mockResolvedValue(
      party({ members: [member(ME, 'OWNER')], pendingInvitations: invitees }),
    );
    vi.mocked(getPartyPopcorn).mockResolvedValue({
      partyId: TEST_PARTY_ID,
      members: [memberPopcorn(ME, [3, 0, 0, 0, 0, 0, 0, 0])],
    });

    const { container } = renderMix();

    expect(await screen.findByText('1명 참여 중 · 3명 초대 수락 대기')).toBeInTheDocument();
    expect(screen.getAllByText('입장 대기')).toHaveLength(3);
    expect(screen.getAllByText('참여 완료')).toHaveLength(1);
    for (let index = 1; index <= 3; index += 1) {
      expect(
        screen.getByRole('img', { name: `친구${index}님의 입장 대기 팝콘` }),
      ).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: '참여 확인하기' })).toHaveAttribute(
      'data-variant',
      'brand',
    );
    await waitFor(() => {
      expect(
        container.querySelector('svg[aria-label="김난다님의 팝콘"]')?.querySelectorAll('image')
          .length,
      ).toBeGreaterThan(1);
    });
    expect(getPartyRecommendations).not.toHaveBeenCalled();
  });

  it('파티원 통이 기계 위로 모이는 것으로 시작하고, 섞기 버튼은 없다', async () => {
    renderMix();

    expect(await screen.findByText('우리의 취향을 모아요')).toBeInTheDocument();
    expect(await screen.findByRole('img', { name: '아직 비어 있는 통' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '팝콘 섞기' })).not.toBeInTheDocument();
    // 진행 표시는 모으기에 있다
    expect(screen.getByText('모으기').closest('li')).toHaveAttribute('aria-current', 'step');
    // 추천은 장면이 도는 동안 받아 둔다
    expect(getPartyRecommendations).toHaveBeenCalledWith(TEST_PARTY_ID);
  });

  it('위 파티원 줄은 API-057의 사진을 쓴다 (업무 -762)', async () => {
    const photo = '/media/avatars/nanda.jpg';
    const base = partyPopcorn();
    vi.mocked(getPartyPopcorn).mockResolvedValue({
      ...base,
      members: base.members.map((item, index) =>
        index === 0 ? { ...item, user: { ...item.user, avatarUrl: photo } } : item,
      ),
    });
    renderMix();

    const first = base.members[0].user.nickname;
    expect(await screen.findByRole('img', { name: `${first}의 프로필 이미지` })).toHaveAttribute(
      'src',
      photo,
    );
  });

  it('누르지 않아도 섞는 장면으로 이어져 새 팝콘이 한 통에 담긴다', async () => {
    renderMix();

    expect(
      await screen.findByText('취향이 섞이는 중', undefined, { timeout: 4000 }),
    ).toBeInTheDocument();
    expect(screen.getByText('섞기').closest('li')).toHaveAttribute('aria-current', 'step');

    expect(
      await screen.findByRole('img', { name: '파티원의 팝콘을 섞은 통' }, { timeout: 8000 }),
    ).toBeInTheDocument();
  });

  it('섞은 뒤 5.5 추천 완성으로 간다', async () => {
    renderMix();

    await waitFor(
      () =>
        expect(screen.getByTestId('location').textContent).toBe(
          `/parties/${TEST_PARTY_ID}/recommendations`,
        ),
      { timeout: 10000 },
    );
  });

  it('추천이 준비 중이면 장면을 끝낸 뒤 추천 실패로 알리고, 추천만 다시 묻는다', async () => {
    vi.mocked(getPartyRecommendations).mockRejectedValue(
      new ApiError(503, 'RECOMMENDATION_UNAVAILABLE', '추천을 준비하고 있습니다.'),
    );

    renderMix();

    expect(
      await screen.findByText('함께 볼 영화를 아직 고르지 못했어요', undefined, { timeout: 10000 }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('추천을 준비하고 있어요. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument();
    expect(screen.queryByText('팝콘을 섞지 못했어요')).not.toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${TEST_PARTY_ID}/popcorn/mix`,
    );

    // 이번에는 준비됐다. 장면을 되풀이하지 않고 5.5로 간다
    vi.mocked(getPartyRecommendations).mockResolvedValue(partyRecommendations());
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(
        `/parties/${TEST_PARTY_ID}/recommendations`,
      ),
    );
    expect(screen.queryByText('취향이 섞이는 중')).not.toBeInTheDocument();
  });

  it('다시 시도의 응답이 오기 전에 떠나면, 늦게 온 성공이 5.5로 끌고 가지 않는다', async () => {
    vi.mocked(getPartyRecommendations).mockRejectedValue(
      new ApiError(503, 'RECOMMENDATION_UNAVAILABLE', '추천을 준비하고 있습니다.'),
    );

    renderMix();

    await screen.findByText('함께 볼 영화를 아직 고르지 못했어요', undefined, { timeout: 10000 });

    // 다시 시도는 아직 응답이 없다. 그 사이에 내 파티로 돌아간다
    let answer: (value: ReturnType<typeof partyRecommendations>) => void = () => undefined;
    vi.mocked(getPartyRecommendations).mockReturnValue(
      new Promise((resolve) => {
        answer = resolve;
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    fireEvent.click(screen.getByRole('button', { name: '내 파티로 돌아가기' }));
    expect(screen.getByTestId('location').textContent).toBe('/party');

    answer(partyRecommendations());
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(screen.getByTestId('location').textContent).toBe('/party');
  });

  it('파티원의 팝콘을 받지 못하면 모을 수 없다고 알린다', async () => {
    vi.mocked(getPartyPopcorn).mockRejectedValue(new Error('실패'));

    renderMix();

    expect(await screen.findByText('파티원의 팝콘을 모으지 못했어요')).toBeInTheDocument();
  });
});

/*
 * 업무 -802. 5.5의 `다른 영화 추천받기`로 들어왔을 때만 API-058에 `refresh=true`를 붙인다.
 */
describe('다시 받기로 들어왔을 때', () => {
  function renderRefresh() {
    return renderWithRouter(<PartyPopcornMixPage />, {
      route: `/parties/${TEST_PARTY_ID}/popcorn/mix`,
      path: '/parties/:partyId/popcorn/mix',
      state: { refresh: true },
    });
  }

  it('추천 요청에 refresh를 붙이고 주소의 state는 지운다', async () => {
    renderRefresh();

    await screen.findByText('우리의 취향을 모아요');
    expect(getPartyRecommendations).toHaveBeenCalledWith(TEST_PARTY_ID, { refresh: true });
    await waitFor(() => expect(screen.getByTestId('location-state').textContent).toBe(''));
  });

  it('첫 진입은 refresh 없이 부른다', async () => {
    renderMix();

    await screen.findByText('우리의 취향을 모아요');
    expect(vi.mocked(getPartyRecommendations).mock.calls[0]).toEqual([TEST_PARTY_ID]);
  });

  it('다시 받기가 실패해 다시 시도하면 그것도 다시 받기다', async () => {
    vi.mocked(getPartyRecommendations).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    renderRefresh();

    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }, { timeout: 8000 }));

    await waitFor(() => expect(getPartyRecommendations).toHaveBeenCalledTimes(2));
    expect(vi.mocked(getPartyRecommendations).mock.calls[1]).toEqual([
      TEST_PARTY_ID,
      { refresh: true },
    ]);
  });
});
