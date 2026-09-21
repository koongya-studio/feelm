/*
 * 5.3 파티 만들기 및 친구 초대 (업무 S15P21E106-304 · -393)
 *
 * `파티 시작하기`가 API-049를 부르고, 고른 친구에게 API-051을 보낸 뒤 그 응답의
 * `partyId`로 5.9에 간다. **초대는 파티를 만든 다음에만 보낼 수 있다.** 화면만 봐서는
 * 순서를 알 수 없으므로 여기서 고정한다.
 *
 * 이름 검증도 함께 본다. API-049가 `name` 필수 · 20자 이하를 요구하는데, 빈 이름으로
 * 보내면 400이 되므로 화면에서 막아야 한다.
 *
 * **일부 초대만 실패한 경우**도 본다. 파티는 이미 만들어졌으므로 다시 누르면 같은 이름의
 * 파티가 하나 더 생긴다. 그 자리를 막아 두지 않으면 쉽게 되살아난다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import PartySetupPage from './index';
import { renderWithRouter } from '../../../test/render';
import { createParty, inviteToParty } from '../../../api/parties';
import { getMyProfile, searchUsers } from '../../../api/users';
import { ApiError } from '../../../api/apiError';
import { mockMyProfile } from '../../../mocks/user';
import { party, TEST_PARTY_ID } from '../../../test/party';

vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return { ...actual, createParty: vi.fn(), inviteToParty: vi.fn() };
});

vi.mock('../../../api/users', async () => {
  const actual = await vi.importActual<typeof import('../../../api/users')>('../../../api/users');

  return { ...actual, getMyProfile: vi.fn(), searchUsers: vi.fn() };
});

const BUDDY = { userId: 2048, nickname: '무비버디', visibility: 'PUBLIC' as const };

beforeEach(() => {
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(createParty).mockReset().mockResolvedValue(party());
  vi.mocked(inviteToParty).mockReset().mockResolvedValue({
    invitationId: 501,
    partyId: TEST_PARTY_ID,
    invitee: BUDDY,
    status: 'PENDING',
    createdAt: '2026-09-10T09:00:00Z',
  });
  vi.mocked(searchUsers)
    .mockReset()
    .mockResolvedValue({ items: [BUDDY], nextCursor: null, hasNext: false });
});

function renderSetup() {
  return renderWithRouter(<PartySetupPage />, { route: '/party/new/setup' });
}

function fillName(name: string) {
  fireEvent.change(screen.getByLabelText('파티 이름'), { target: { value: name } });
}

async function pickBuddy() {
  fireEvent.change(screen.getByLabelText('닉네임으로 친구 찾기'), { target: { value: '무비' } });
  fireEvent.click(screen.getByRole('button', { name: '검색' }));

  fireEvent.click(await screen.findByRole('button', { name: /무비버디/ }));
}

describe('5.3 파티 만들기', () => {
  it('이름을 넣고 친구를 초대해 시작하면 파티가 만들어지고 5.9로 간다', async () => {
    renderSetup();

    fillName('금요 심야 상영회');
    await pickBuddy();
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(
        `/parties/${TEST_PARTY_ID}/popcorn/mix`,
      ),
    );
    expect(createParty).toHaveBeenCalledWith('금요 심야 상영회');
  });

  it('이름이 비면 보내지 않고 알린다', async () => {
    renderSetup();

    await pickBuddy();
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('파티 이름을 입력해 주세요.');
    expect(screen.getByTestId('location').textContent).toBe('/party/new/setup');
    expect(createParty).not.toHaveBeenCalled();
  });

  it('인원 표시는 5.2에서 고른 정원을 따른다', () => {
    /*
     * 2인을 골랐는데 `1/4명`이 나오던 것을 고쳤다(업무 -610). 계약(API-049)에 정원을 보낼
     * 필드가 없어 서버는 4로 두지만, 이 화면이 말하는 상한은 사용자가 고른 수다.
     */
    renderSetup();
    expect(screen.getByText('1/2명 · 최대 2명까지')).toBeInTheDocument();
  });

  it('5.2에서 3인을 골랐으면 3명이 상한이다', () => {
    render(
      <MemoryRouter initialEntries={[{ pathname: '/party/new/setup', state: { size: 3 } }]}>
        <PartySetupPage />
      </MemoryRouter>,
    );

    expect(screen.getByText('1/3명 · 최대 3명까지')).toBeInTheDocument();
  });

  it('초대한 친구가 없으면 시작할 수 없다', () => {
    /*
     * 파티는 나까지 2명부터다(5.2 안내 · `PARTY_MIN_MEMBERS`). 혼자인 파티를 만들면 5.9
     * 이후가 할 일이 없다(업무 -610).
     */
    renderSetup();

    fillName('금요 심야 상영회');

    expect(screen.getByRole('button', { name: '파티 시작하기' })).toBeDisabled();
    expect(screen.getByText(/1명 이상 초대해야 시작할 수 있어요/)).toBeInTheDocument();
    expect(createParty).not.toHaveBeenCalled();
  });

  it('두 번 눌러도 파티는 하나만 만든다', async () => {
    let resolveParty: (value: ReturnType<typeof party>) => void = () => undefined;
    vi.mocked(createParty).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveParty = resolve;
        }),
    );

    renderSetup();
    fillName('금요 심야 상영회');
    await pickBuddy();

    const start = screen.getByRole('button', { name: '파티 시작하기' });
    fireEvent.click(start);
    fireEvent.click(start);
    resolveParty(party());

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(
        `/parties/${TEST_PARTY_ID}/popcorn/mix`,
      ),
    );
    expect(createParty).toHaveBeenCalledTimes(1);
  });
});

describe('5.3 친구 초대', () => {
  it('닉네임으로 찾은 친구를 고르면 참여자 자리에 들어간다', async () => {
    renderSetup();

    await pickBuddy();

    expect(searchUsers).toHaveBeenCalledWith('무비');
    expect(await screen.findByText('2/2명 · 최대 2명까지')).toBeInTheDocument();
  });

  it('파티를 만든 뒤에 초대를 보낸다', async () => {
    renderSetup();

    fillName('금요 심야 상영회');
    await pickBuddy();
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    await waitFor(() => expect(inviteToParty).toHaveBeenCalledWith(TEST_PARTY_ID, 2048));
    // 초대에는 파티 번호가 필요하다. 만들기가 먼저다
    expect(vi.mocked(createParty).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(inviteToParty).mock.invocationCallOrder[0],
    );
  });

  it('두 글자가 안 되면 검색하지 않는다', async () => {
    renderSetup();

    fireEvent.change(screen.getByLabelText('닉네임으로 친구 찾기'), { target: { value: '무' } });
    fireEvent.click(screen.getByRole('button', { name: '검색' }));

    expect(await screen.findByText('닉네임을 2자 이상 입력해 주세요.')).toBeInTheDocument();
    expect(searchUsers).not.toHaveBeenCalled();
  });

  it('초대만 실패하면 파티를 다시 만들지 않는다', async () => {
    vi.mocked(inviteToParty).mockRejectedValue(
      new ApiError(409, 'PARTY_FULL', '파티가 가득 찼습니다.'),
    );

    renderSetup();

    fillName('금요 심야 상영회');
    await pickBuddy();
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    expect(await screen.findByText(/초대를 보내지 못했어요/)).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/party/new/setup');

    // 다시 누르면 만들어 둔 파티로 간다
    fireEvent.click(screen.getByRole('button', { name: '파티로 이동' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(
        `/parties/${TEST_PARTY_ID}/popcorn/mix`,
      ),
    );
    expect(createParty).toHaveBeenCalledTimes(1);
  });
});
