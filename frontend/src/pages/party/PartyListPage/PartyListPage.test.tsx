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
import { getMyInvitations, getMyParties } from '../../../api/parties';
import { invitation, party, TEST_PARTY_ID } from '../../../test/party';

// 5.2가 같은 모듈에서 정원 상수를 읽으므로 나머지는 실제 것을 남긴다
vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return { ...actual, getMyParties: vi.fn(), getMyInvitations: vi.fn() };
});

beforeEach(() => {
  vi.mocked(getMyParties).mockReset().mockResolvedValue([party()]);
  // 초대는 받지 않은 것이 보통 상태다. 필요한 테스트만 채운다
  vi.mocked(getMyInvitations).mockReset().mockResolvedValue([]);
});

describe('5.1 파티 시작', () => {
  it('참여 중인 파티와 파티원을 보여준다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByText('금요 심야 상영회')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '파티팝콘' })).toBeInTheDocument();
    expect(screen.getByText('김난다 × 무비버디')).toBeInTheDocument();
  });

  it('필름 · MY와 같은 공통 헤더로 검색 · 알림에 바로 간다 (업무 -676)', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByRole('link', { name: '검색' })).toHaveAttribute('href', '/search');
    expect(screen.getByRole('link', { name: /^알림/ })).toHaveAttribute('href', '/notifications');
    // 화면 제목은 여전히 하나다
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
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

  /*
   * 업무 -595. 인앱 알림 외에도 파티 탭에서 받은 초대를 다시 찾을 수 있어야 한다.
   * 이 구역이 사라지면 알림을 숨긴 사용자가 초대를 열 길이 없어진다.
   */
  it('받은 초대를 API-052로 그리고 5.6으로 보낸다', async () => {
    vi.mocked(getMyInvitations).mockResolvedValue([invitation()]);

    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByText('무비버디 님의 파티')).toBeInTheDocument();
    // `2인`은 나까지 센 값이다. API-052 `memberCount`는 1(파티장뿐)이다
    expect(screen.getByText('2인 · 오늘 저녁 같이 볼 영화')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /무비버디 님의 파티/ }));

    expect(screen.getByTestId('location').textContent).toBe('/parties/invitations/501');
  });

  it('대기 중 초대가 없으면 구역을 그리지 않는다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });
    await screen.findByText('금요 심야 상영회');

    expect(screen.queryByText(/받은 초대/)).not.toBeInTheDocument();
  });

  // 초대와 파티는 다른 요청이다. 한쪽이 실패해도 다른 쪽은 보여준다
  it('초대를 받지 못해도 파티 목록은 남는다', async () => {
    vi.mocked(getMyInvitations).mockRejectedValue(new Error('실패'));

    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByText('금요 심야 상영회')).toBeInTheDocument();
    expect(screen.getByText(/받은 초대를 불러오지 못했어요/)).toBeInTheDocument();
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
