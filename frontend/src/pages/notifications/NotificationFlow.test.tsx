/*
 * 알림 — 이동 경로 (업무 S15P21E106-308)
 *
 * 8.1의 네 알림은 전부 다른 화면을 가리킨다. **목적지가 번호로 찾는 화면**(2.4-A · 5.6)은 그
 * 번호의 데이터가 실제로 있어야 이어진다 — 검색이 404면 화면은 열려도 경로는 끊긴 것이다
 * (업무 -304에서 같은 이유로 HIGH 지적을 받았다).
 *
 * 읽음 표식은 세션 저장소에 남는다. 눌렀다가 돌아와서 사라졌는지 본다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import NotificationListPage from './NotificationListPage';
import PushPreviewPage from './PushPreviewPage';
import FilmPage from '../film/FilmPage';
import PartyInvitationPage from '../party/PartyInvitationPage';
import WatchConfirmPage from '../watch/WatchConfirmPage';
import { getMyProfile } from '../../api/users';
import { mockOtts } from '../../mocks/otts';
import { resetSessionStore } from '../../mocks/sessionStore';
import { mockMyProfile } from '../../mocks/user';
import { ROUTE_PATTERN } from '../../routes/paths';
import { invitation } from '../../test/party';
import type { WatchRecordListItem } from '../../types/watch';

vi.mock('../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

/*
 * 알림 아이콘이 **홈에서 3.1 필름 헤더로 옮겨왔다**(업무 -461). 필름이 부르는 필름
 * 목록 · 팝콘 · 리포트는 실제 호출이라 가로채지 않으면 화면이 실패 상태로 떨어져
 * 헤더의 알림 링크가 나오지 않는다. 이 시험이 잡는 것은 알림 경로라 최소값만 준다.
 */
vi.mock('../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/taste')>()),
  getMyFrames: vi.fn().mockResolvedValue({
    totalCount: 1,
    items: [
      {
        frameId: 1,
        movie: { movieId: 101, title: '영화', posterUrl: null },
        flavorCode: 'FLAVOR_A',
        score: 4,
        createdAt: '2026-09-01T00:00:00Z',
      },
    ],
    nextCursor: null,
    hasNext: false,
  }),
  getPopcornBucket: vi.fn().mockResolvedValue({ totalCount: 0, flavors: [] }),
}));

/*
 * 5.6 파티 초대 수신이 API-052 · API-054를 부른다. 연동(-393) 뒤에는 실제 호출이라
 * 가로채지 않으면 초대를 찾지 못한 상태가 되어 `거절` 버튼이 아예 없다.
 */
vi.mock('../../api/parties', async () => {
  const actual = await vi.importActual<typeof import('../../api/parties')>('../../api/parties');

  return {
    ...actual,
    getMyParties: vi.fn().mockResolvedValue([]),
    getInvitation: vi.fn(),
    acceptInvitation: vi.fn(),
    declineInvitation: vi.fn(),
  };
});

// 2.4-A 감상 확인이 OTT 기준정보를 부른다. 연동(-379) 뒤에는 실제 호출이다
vi.mock('../../api/otts', () => ({
  getOtts: vi.fn(),
  getMyOttIds: vi.fn(),
  setMyOttIds: vi.fn(),
}));

/*
 * 2.4-A가 감상 기록을 찾고 확인 응답을 보낸다. 연동(-477) 뒤에는 실제 호출이라
 * 가로채지 않으면 기록을 찾지 못해 `감상 기록을 찾을 수 없어요`가 된다.
 *
 * **알림은 아직 목업이라 이 어긋남이 실제로 있다.** 8.1의 감상 확인 알림이 가리키는
 * 번호는 목업 값이라 서버에 없다. 알림이 실제 API가 되면 서버가 준 번호가 온다.
 * 이 시험이 잡으려는 것은 알림 → 2.4-A → 평가 경로이므로, 그 번호에 기록이 있는
 * 상태를 서버 대역으로 세운다.
 */
vi.mock('../../api/watch', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/watch')>()),
  findWatchRecord: vi.fn(),
  confirmWatch: vi.fn(),
}));

const { getOtts, getMyOttIds } = await import('../../api/otts');
const { declineInvitation, getInvitation } = await import('../../api/parties');
const { findWatchRecord, confirmWatch } = await import('../../api/watch');

/** 8.1 감상 확인 알림이 가리키는 기록(4004 · 영화 104 · 넷플릭스) */
function linkClickedRecord(): WatchRecordListItem {
  const clickedAt = '2026-09-10T12:00:00Z';

  return {
    watchRecordId: 4004,
    movie: { movieId: 104, title: '영화 제목 104', posterUrl: null },
    status: 'LINK_CLICKED',
    ott: { ottId: mockOtts[0].ottId, code: mockOtts[0].code },
    clickedAt,
    confirmDueAt: clickedAt,
    expiresAt: clickedAt,
    watchedAt: null,
    ratingId: null,
  };
}

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  // 8.1의 파티 초대 알림이 가리키는 번호(501)를 그대로 쓴다
  vi.mocked(getInvitation).mockReset().mockResolvedValue(invitation());
  vi.mocked(declineInvitation)
    .mockReset()
    .mockResolvedValue({ invitationId: 501, status: 'DECLINED' });
  vi.mocked(getOtts).mockReset().mockResolvedValue(mockOtts);
  vi.mocked(getMyOttIds).mockReset().mockResolvedValue([mockOtts[0].ottId]);
  vi.mocked(findWatchRecord).mockReset().mockResolvedValue(linkClickedRecord());
  vi.mocked(confirmWatch).mockReset().mockResolvedValue({
    watchRecordId: 4004,
    status: 'WATCHED_UNRATED',
    watchedAt: '2026-09-12T12:00:00Z',
  });
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderApp(entries: string[]) {
  return render(
    <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
      <Routes>
        <Route path={ROUTE_PATTERN.film} element={<FilmPage />} />
        <Route path={ROUTE_PATTERN.notifications} element={<NotificationListPage />} />
        <Route path={ROUTE_PATTERN.pushPreview} element={<PushPreviewPage />} />
        <Route path={ROUTE_PATTERN.watchConfirm} element={<WatchConfirmPage />} />
        <Route path={ROUTE_PATTERN.partyInvitation} element={<PartyInvitationPage />} />
        <Route path="*" element={<p>다른 화면</p>} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

const location = () => screen.getByTestId('location').textContent;

async function findRow(title: string) {
  return (await screen.findByText(title)).closest('button') as HTMLButtonElement;
}

describe('3.1 → 8.1 → 2.4-A', () => {
  it('감상 확인 알림이 그 감상 기록으로 가고, 돌아오면 읽음이다', async () => {
    const { unmount } = renderApp([ROUTE_PATTERN.film]);

    fireEvent.click(await screen.findByRole('link', { name: '알림' }));
    expect(location()).toBe('/notifications');

    const row = await findRow('영화 잘 보셨나요?');
    expect(row).toHaveTextContent('읽지 않음');
    expect(row).toHaveTextContent('방금 전');

    fireEvent.click(row);
    await waitFor(() => expect(location()).toBe('/watch-records/4004/confirm'));

    // 2.4-A가 그 기록의 영화(104)를 보여주고, `봤어요`가 그 영화의 평가로 간다
    fireEvent.click(await screen.findByRole('button', { name: '봤어요' }));
    await waitFor(() => expect(location()).toBe('/movies/104/rate'));
    unmount();

    renderApp([ROUTE_PATTERN.notifications]);
    expect(await findRow('영화 잘 보셨나요?')).not.toHaveTextContent('읽지 않음');
  });

  it('탭은 목록만 거른다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    await findRow('영화 잘 보셨나요?');
    fireEvent.click(screen.getByRole('tab', { name: '추천' }));

    const panel = within(screen.getByRole('tabpanel'));
    expect(panel.getAllByRole('button')).toHaveLength(1);
    expect(panel.getByText('오늘의 추천이 도착했어요')).toBeInTheDocument();
    expect(location()).toBe('/notifications');
  });

  it('오늘의 추천은 2.2, 필름 추가는 3.1로 간다', async () => {
    const { unmount } = renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow('오늘의 추천이 도착했어요'));
    await waitFor(() => expect(location()).toBe('/recommendations'));
    unmount();

    renderApp([ROUTE_PATTERN.notifications]);
    fireEvent.click(await findRow('필름에 새 프레임이 감겼어요'));
    await waitFor(() => expect(location()).toBe('/film'));
  });
});

describe('8.1 → 5.6 ⇢ 8.1', () => {
  it('파티 초대 알림에서 초대를 거절하면 알림 목록으로 돌아온다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow('파티 모드 초대'));
    await waitFor(() => expect(location()).toBe('/parties/invitations/501'));

    fireEvent.click(await screen.findByRole('button', { name: /거절/ }));

    await waitFor(() => expect(location()).toBe('/notifications'));
    expect(await findRow('파티 모드 초대')).not.toHaveTextContent('읽지 않음');
  });
});

describe('8.2 → 2.4-A', () => {
  it('잠금화면 알림을 누르면 감상 확인으로 간다', async () => {
    renderApp([ROUTE_PATTERN.pushPreview]);

    fireEvent.click(await screen.findByRole('button', { name: /영화 잘 보셨나요\?/ }));

    await waitFor(() => expect(location()).toBe('/watch-records/4004/confirm'));
  });
});
