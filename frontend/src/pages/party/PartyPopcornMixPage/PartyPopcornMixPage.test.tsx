/*
 * 5.9 팝콘 섞기 (업무 S15P21E106-462)
 *
 * 세 가지를 고정한다.
 *
 * **섞는 것은 사용자가 시작한다**(사용자 지시 2026-09-17). 누르기 전에는 장면이 돌지
 * 않고, 누르면 파티원의 팝콘이 기계로 들어가 새 팝콘 한 통이 담긴다.
 *
 * **버튼은 섞는 동안 사라진다.** 남아 있으면 두 번 눌려 단계가 겹쳐 돈다.
 *
 * **담긴 뒤에 5.5로 간다.** 추천(API-058)이 실패하면 보내지 않고 이 화면에서 알린다 —
 * 그러지 않으면 5.5가 빈 화면으로 시작한다.
 *
 * `[!]` 이 파일은 **테스트 자체의 제한 시간**을 늘려 둔다(업무 -617). 섞기 연출이 약 4.8초인데
 * Vitest 기본 제한이 5초라, 전체 실행처럼 러너가 붐비면 그 안에 끝나지 않는다. `findBy` ·
 * `waitFor`에 준 `timeout`은 그 대기 헬퍼의 제한일 뿐 테스트의 제한을 늘리지 않는다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PartyPopcornMixPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getParty, getPartyPopcorn, getPartyRecommendations } from '../../../api/parties';
import { party, partyPopcorn, partyRecommendations, TEST_PARTY_ID } from '../../../test/party';

/*
 * 연출을 끝까지 기다리는 테스트가 있어 이 파일의 제한 시간을 올린다. `it`마다 제한을 붙이면
 * Prettier가 본문을 모두 들여써 변경이 커지므로 파일 단위로 한 번 준다. 제한 시간은 상한이라
 * 연출을 기다리지 않는 테스트가 느려지지는 않는다.
 */
vi.setConfig({ testTimeout: 10000 });

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

describe('5.9 팝콘 섞기', () => {
  it('누르기 전에는 섞지 않는다', async () => {
    renderMix();

    expect(await screen.findByText('취향을 섞어 볼까요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '팝콘 섞기' })).toBeInTheDocument();
    // 통은 아직 비어 있다
    expect(screen.getByRole('img', { name: '아직 비어 있는 통' })).toBeInTheDocument();
    expect(getPartyRecommendations).not.toHaveBeenCalled();
  });

  it('누르면 섞는 장면이 돌고 새 팝콘이 한 통에 담긴다', async () => {
    renderMix();

    fireEvent.click(await screen.findByRole('button', { name: '팝콘 섞기' }));

    expect(screen.getByText('취향이 섞이는 중')).toBeInTheDocument();
    // 같은 버튼이 남아 있으면 단계가 겹쳐 돈다
    expect(screen.queryByRole('button', { name: '팝콘 섞기' })).not.toBeInTheDocument();

    expect(
      await screen.findByRole('img', { name: '파티원의 팝콘을 섞은 통' }, { timeout: 8000 }),
    ).toBeInTheDocument();
  });

  it('섞은 뒤 5.5 추천 완성으로 간다', async () => {
    renderMix();

    fireEvent.click(await screen.findByRole('button', { name: '팝콘 섞기' }));

    await waitFor(
      () =>
        expect(screen.getByTestId('location').textContent).toBe(
          `/parties/${TEST_PARTY_ID}/recommendations`,
        ),
      { timeout: 8000 },
    );
  });

  it('추천을 받지 못하면 5.5로 보내지 않고 알린다', async () => {
    vi.mocked(getPartyRecommendations).mockRejectedValue(new Error('실패'));

    renderMix();

    fireEvent.click(await screen.findByRole('button', { name: '팝콘 섞기' }));

    expect(
      await screen.findByText('팝콘을 섞지 못했어요', undefined, { timeout: 8000 }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe(
      `/parties/${TEST_PARTY_ID}/popcorn/mix`,
    );
  });

  it('파티원의 팝콘을 받지 못하면 섞을 수 없다', async () => {
    vi.mocked(getPartyPopcorn).mockRejectedValue(new Error('실패'));

    renderMix();

    expect(await screen.findByText('팝콘을 섞지 못했어요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '팝콘 섞기' })).not.toBeInTheDocument();
  });
});
