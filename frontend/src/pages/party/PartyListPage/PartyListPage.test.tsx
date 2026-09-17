/*
 * 5.1 파티 시작 · 5.2 인원 설정 (업무 S15P21E106-304 · -393 · -462)
 *
 * 이동표의 `5.1 → 5.2`와 `5.1 → 5.8`을 고정한다. 파티방을 고르면 5.8 파티원 팝콘으로
 * 가는데, 목록 화면만 봐서는 어디로 가는지 알 수 없다.
 *
 * 사용자 시안(업무 -462)이 머리말과 버튼 문구를 바꿨고, `초대 코드로 참여하기`가
 * 생겼다. 그 버튼은 **계약에 경로가 없어** 누르면 준비 중임을 알린다.
 *
 * 5.2는 **계약이 정한 정원(4)만 고를 수 있어야 한다.** 와이어프레임은 6인까지 그렸으므로
 * 나중에 누군가 와이어프레임을 보고 되돌리기 쉬운 자리다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PartyListPage from './index';
import PartySizePage from '../PartySizePage';
import { renderWithRouter } from '../../../test/render';
import { getMyParties } from '../../../api/parties';
import { party, TEST_PARTY_ID } from '../../../test/party';

// 5.2가 같은 모듈에서 정원 상수를 읽으므로 나머지는 실제 것을 남긴다
vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return { ...actual, getMyParties: vi.fn() };
});

beforeEach(() => {
  vi.mocked(getMyParties).mockReset().mockResolvedValue([party()]);
});

describe('5.1 파티 시작', () => {
  it('참여 중인 파티와 파티원을 보여준다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByText('금요 심야 상영회')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '파티팝콘' })).toBeInTheDocument();
    expect(screen.getByText('김난다 × 무비버디')).toBeInTheDocument();
  });

  it('파티방을 고르면 5.8 파티원 팝콘으로 간다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });
    await screen.findByText('금요 심야 상영회');

    fireEvent.click(screen.getByRole('button', { name: /금요 심야 상영회/ }));

    expect(screen.getByTestId('location').textContent).toBe(`/parties/${TEST_PARTY_ID}/popcorn`);
  });

  it('파티 만들기는 5.2로 간다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });
    await screen.findByText('금요 심야 상영회');

    fireEvent.click(screen.getByRole('button', { name: '파티 만들기' }));

    expect(screen.getByTestId('location').textContent).toBe('/party/new');
  });

  it('파티가 없으면 담기지 않은 통을 두고 만들기를 권한다', async () => {
    vi.mocked(getMyParties).mockResolvedValue([]);

    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(
      await screen.findByRole('img', { name: '아직 파티원의 팝콘이 담기지 않은 통' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '파티 만들기' })).toBeInTheDocument();
  });

  /*
   * 계약에 초대 코드 경로가 없다(API-051은 닉네임 검색으로 받은 `userId`만 받는다).
   * 시안이 확정한 자리라 버튼은 두지만, 되는 것처럼 보이면 안 된다.
   */
  it('초대 코드로 참여하기는 준비 중임을 알린다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });
    await screen.findByText('금요 심야 상영회');

    fireEvent.click(screen.getByRole('button', { name: '초대 코드로 참여하기' }));

    expect(screen.getByText('초대 코드로 참여하기는 준비 중이에요')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/party');
  });

  it('목록을 받지 못하면 다시 시도할 수 있다', async () => {
    vi.mocked(getMyParties).mockRejectedValue(new Error('실패'));

    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByText('파티 목록을 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });
});

describe('5.2 새 파티 인원 설정', () => {
  it('계약이 정한 2~4인만 고를 수 있다', () => {
    renderWithRouter(<PartySizePage />, { route: '/party/new' });

    // 와이어프레임은 6인까지 그렸지만 API-049가 정한 정원은 4다
    expect(screen.getByRole('radio', { name: '2인' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '4인' })).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: '5인' })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: '6인' })).not.toBeInTheDocument();
  });

  it('고른 인원만큼 친구 자리를 그린다', () => {
    renderWithRouter(<PartySizePage />, { route: '/party/new' });

    // 기본값 2인 — 내 자리 하나와 친구 하나
    expect(screen.getByText('친구들을 등록해 주세요 (0/1)')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('radio', { name: '4인' }));

    expect(screen.getByText('친구들을 등록해 주세요 (0/3)')).toBeInTheDocument();
    expect(screen.getByText('친구 3')).toBeInTheDocument();
  });
});
