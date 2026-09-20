/*
 * 5.1 파티 시작 · 5.2 인원 설정 (업무 S15P21E106-304 · -393 · -462 · -723)
 *
 * 이동표의 `5.1 → 5.2`와 `5.1 → 5.8`을 고정한다. 파티방을 고르면 5.8 파티원 팝콘으로
 * 가는데, 목록 화면만 봐서는 어디로 가는지 알 수 없다.
 *
 * **사용자 시안 2026-09-20(업무 -723)이 이 화면의 구성을 바꿨다.** 목록 한 줄이던 파티가
 * 대표 한 방을 앞세운 강조 카드와 그 뒤의 카드 줄로 갈렸다. 여기서 고정하는 것은 둘이다 —
 * **대표 파티는 아래 줄에서 빠진다**(같은 이름이 두 번 서면 어느 것을 눌러야 하는지 모른다),
 * 그리고 **이동표는 그대로다**(강조 카드의 주 버튼도 5.8로 간다).
 *
 * 얼굴 사진은 계약에 없어 닉네임 첫 글자를 쓴다(API-050 · API-052는 `userId`와 닉네임뿐).
 * 이름이 화면에 남는지까지 본다 — 첫 글자만 남으면 누구인지 알 수 없다.
 *
 * 5.2는 **계약이 정한 정원(4)만 고를 수 있어야 한다.** 와이어프레임은 6인까지 그렸으므로
 * 나중에 누군가 와이어프레임을 보고 되돌리기 쉬운 자리다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PartyListPage from './index';
import PartySizePage from '../PartySizePage';
import { renderWithRouter } from '../../../test/render';
import { getMyInvitations, getMyParties, getPartyPopcorn } from '../../../api/parties';
import { getPopcornFlavors } from '../../../api/taste';
import { flavors, invitation, party, partyPopcorn, TEST_PARTY_ID } from '../../../test/party';

// 5.2가 같은 모듈에서 정원 상수를 읽으므로 나머지는 실제 것을 남긴다
vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return {
    ...actual,
    getMyParties: vi.fn(),
    getMyInvitations: vi.fn(),
    getPartyPopcorn: vi.fn(),
  };
});

vi.mock('../../../api/taste', async () => {
  const actual = await vi.importActual<typeof import('../../../api/taste')>('../../../api/taste');

  return { ...actual, getPopcornFlavors: vi.fn() };
});

beforeEach(() => {
  vi.mocked(getMyParties).mockReset().mockResolvedValue([party()]);
  // 초대는 받지 않은 것이 보통 상태다. 필요한 테스트만 채운다
  vi.mocked(getMyInvitations).mockReset().mockResolvedValue([]);
  vi.mocked(getPartyPopcorn).mockReset().mockResolvedValue(partyPopcorn());
  vi.mocked(getPopcornFlavors).mockReset().mockResolvedValue(flavors());
});

describe('5.1 파티 시작', () => {
  it('대표 파티를 강조 카드로 세우고 파티원을 이름까지 보여준다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByRole('heading', { name: '금요 심야 상영회' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '파티팝콘' })).toBeInTheDocument();
    // 얼굴 사진이 없어 첫 글자를 쓰지만 이름이 사라지면 누구인지 알 수 없다
    expect(screen.getByText('김난다')).toBeInTheDocument();
    expect(screen.getByText('무비버디')).toBeInTheDocument();
    expect(screen.getByText('2 / 4명 참여')).toBeInTheDocument();
  });

  it('파티원의 팝콘을 섞어 공통 취향으로 세운다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });

    // 많이 담긴 맛부터 셋이다(`맛1` 21알 · `맛2` 19알 · `맛3` 9알)
    expect(await screen.findByText('우리의 공통 취향')).toBeInTheDocument();
    expect(screen.getByText('맛1')).toBeInTheDocument();
    expect(screen.getByText('맛2')).toBeInTheDocument();
    expect(screen.getByText('맛3')).toBeInTheDocument();
    expect(screen.queryByText('맛4')).not.toBeInTheDocument();
  });

  it('맛 이름을 받지 못하면 칩을 세우지 않는다 — 코드를 그대로 보여주지 않는다', async () => {
    vi.mocked(getPopcornFlavors).mockRejectedValue(new Error('실패'));

    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByRole('heading', { name: '금요 심야 상영회' })).toBeInTheDocument();
    expect(screen.queryByText('FLAVOR_A')).not.toBeInTheDocument();
  });

  it('필름 · MY와 같은 공통 헤더로 검색 · 알림에 바로 간다 (업무 -676)', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByRole('link', { name: '검색' })).toHaveAttribute('href', '/search');
    expect(screen.getByRole('link', { name: /^알림/ })).toHaveAttribute('href', '/notifications');
    // 화면 제목은 여전히 하나다
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('강조 카드의 주 버튼은 5.8 파티원 팝콘으로 간다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });
    await screen.findByRole('heading', { name: '금요 심야 상영회' });

    fireEvent.click(screen.getByRole('button', { name: /파티 팝콘 보기/ }));

    expect(screen.getByTestId('location').textContent).toBe(`/parties/${TEST_PARTY_ID}/popcorn`);
  });

  /*
   * 대표 파티가 아래 줄에도 서면 같은 이름이 두 번 나온다. 시안이 둘을 나눈 이유다.
   */
  it('대표 파티는 아래 카드 줄에서 빠지고 나머지만 선다', async () => {
    vi.mocked(getMyParties).mockResolvedValue([
      party(),
      party({ partyId: 9101, name: '주말 애니 모임' }),
    ]);

    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByText('참여 중인 파티 2')).toBeInTheDocument();
    expect(screen.getAllByText('금요 심야 상영회')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: /주말 애니 모임/ }));

    expect(screen.getByTestId('location').textContent).toBe('/parties/9101/popcorn');
  });

  it('파티가 하나뿐이면 아래 카드 줄을 그리지 않는다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });

    await screen.findByRole('heading', { name: '금요 심야 상영회' });
    expect(screen.queryByText(/참여 중인 파티/)).not.toBeInTheDocument();
  });

  it('파티 만들기는 5.2로 간다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });
    await screen.findByRole('heading', { name: '금요 심야 상영회' });

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
    await screen.findByRole('heading', { name: '금요 심야 상영회' });

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

    expect(await screen.findByText('오늘 저녁 같이 볼 영화')).toBeInTheDocument();
    expect(screen.getByText('무비버디님의 파티에 초대받았어요!')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /초대받았어요/ }));

    expect(screen.getByTestId('location').textContent).toBe('/parties/invitations/501');
  });

  it('대기 중 초대가 없으면 구역을 그리지 않는다', async () => {
    renderWithRouter(<PartyListPage />, { route: '/party' });
    await screen.findByRole('heading', { name: '금요 심야 상영회' });

    expect(screen.queryByText(/받은 초대/)).not.toBeInTheDocument();
  });

  // 초대와 파티는 다른 요청이다. 한쪽이 실패해도 다른 쪽은 보여준다
  it('초대를 받지 못해도 파티 목록은 남는다', async () => {
    vi.mocked(getMyInvitations).mockRejectedValue(new Error('실패'));

    renderWithRouter(<PartyListPage />, { route: '/party' });

    expect(await screen.findByRole('heading', { name: '금요 심야 상영회' })).toBeInTheDocument();
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
    expect(screen.getByText('다음 화면에서 친구 1명을 초대해요')).toBeInTheDocument();
    expect(screen.getByText('친구 1')).toBeInTheDocument();
    expect(screen.queryByText('친구 2')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('radio', { name: '4인' }));

    // 안내 문구와 자리 수가 곧바로 같이 바뀐다
    expect(screen.getByText('다음 화면에서 친구 3명을 초대해요')).toBeInTheDocument();
    expect(screen.getByText('친구 3')).toBeInTheDocument();
  });

  it('이 화면에서 친구를 등록하는 것처럼 말하지 않는다', () => {
    // 실제 초대는 5.3에서 한다 — API-051은 파티가 만들어진 뒤에 부른다(업무 -697)
    renderWithRouter(<PartySizePage />, { route: '/party/new' });

    expect(screen.queryByText(/등록해 주세요/)).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('고른 인원은 aria-checked와 함께 색이 아닌 체크로도 표시된다', () => {
    renderWithRouter(<PartySizePage />, { route: '/party/new' });

    fireEvent.click(screen.getByRole('radio', { name: '3인' }));

    const picked = screen.getByRole('radio', { name: '3인' });
    expect(picked).toHaveAttribute('aria-checked', 'true');
    expect(picked.querySelector('svg')).not.toBeNull();
    expect(screen.getByRole('radio', { name: '2인' }).querySelector('svg')).toBeNull();
  });
});
