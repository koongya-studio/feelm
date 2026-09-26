/* API-070~074 알림센터 표시·상태 변경·대상 이동 흐름. */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import NotificationListPage from './NotificationListPage';
import PushPreviewPage from './PushPreviewPage';
import FilmPage from '../film/FilmPage';
import { AuthContext } from '../../contexts/authContext';
import { testAuth } from '../../test/auth';
import { ROUTE_PATTERN } from '../../routes/paths';
import { notifyNotificationChange } from '../../api/notificationStream';
import type { NotificationItem } from '../../types/notification';

vi.mock('../../api/notifications', () => ({
  getNotifications: vi.fn(),
  getLatestPushNotification: vi.fn(),
  getUnreadNotificationCount: vi.fn(),
  readNotification: vi.fn(),
  readAllNotifications: vi.fn(),
  dismissNotification: vi.fn(),
}));

vi.mock('../../api/taste', () => ({
  getMyFrames: vi.fn().mockResolvedValue({
    totalCount: 0,
    items: [],
    nextCursor: null,
    hasNext: false,
  }),
  getPopcornBucket: vi.fn().mockResolvedValue({ totalCount: 0, flavors: [] }),
}));

// 파티 초대 수락 알림은 이동 전에 내 파티 목록에서 파티를 찾는다(업무 -866)
vi.mock('../../api/parties', () => ({ getParty: vi.fn(), getMyInvitations: vi.fn() }));

vi.mock('../../api/reports', () => ({
  getLatestReadyReport: vi.fn().mockResolvedValue(null),
}));

const {
  dismissNotification,
  getLatestPushNotification,
  getNotifications,
  getUnreadNotificationCount,
  readAllNotifications,
  readNotification,
} = await import('../../api/notifications');
const { getParty, getMyInvitations } = await import('../../api/parties');

/** 파티 초대 수락 알림이 가리키는 파티. 기본은 참여 중인 활성 파티다 */
function acceptedParty(status: 'ACTIVE' | 'CLOSED') {
  const owner = { userId: 7, nickname: '테스트 사용자', avatarUrl: null };

  return {
    partyId: 77,
    name: '금요 심야 상영회',
    status,
    owner,
    members: [{ ...owner, role: 'OWNER' as const, joinedAt: '2026-09-18T09:00:00Z' }],
    maxMembers: 4,
    createdAt: '2026-09-18T09:00:00Z',
  };
}

const watch: NotificationItem = {
  notificationId: 1,
  type: 'WATCH_CONFIRM',
  title: '영화 잘 보셨나요?',
  body: '감상 확인하러 가기',
  targetType: 'WATCH_RECORD',
  targetId: 4004,
  readAt: null,
  notifiedAt: new Date().toISOString(),
};

const invite: NotificationItem = {
  notificationId: 2,
  type: 'PARTY_INVITE',
  title: '파티팝콘 초대',
  body: '새로운 파티팝콘 초대가 도착했어요.',
  targetType: 'PARTY_INVITATION',
  targetId: 501,
  readAt: null,
  notifiedAt: '2026-09-17T09:00:00Z',
};

const accepted: NotificationItem = {
  notificationId: 3,
  type: 'PARTY_INVITE_ACCEPTED',
  title: '파티팝콘 초대 수락',
  body: 'film_daily 님이 파티팝콘에 참여했어요.',
  targetType: 'PARTY',
  targetId: 77,
  readAt: null,
  notifiedAt: '2026-09-18T09:00:00Z',
};

const report: NotificationItem = {
  notificationId: 4,
  type: 'REPORT_ISSUED',
  title: '취향 리포트 발급',
  body: '2026년 상반기 취향 리포트가 도착했어요.',
  targetType: 'TASTE_REPORT',
  targetId: 301,
  readAt: null,
  notifiedAt: '2026-09-16T09:00:00Z',
};

beforeEach(() => {
  vi.mocked(getNotifications).mockReset().mockResolvedValue([accepted, watch, invite, report]);
  vi.mocked(getLatestPushNotification).mockReset().mockResolvedValue(watch);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(4);
  vi.mocked(readNotification).mockReset().mockResolvedValue();
  vi.mocked(readAllNotifications).mockReset().mockResolvedValue();
  vi.mocked(dismissNotification).mockReset().mockResolvedValue();
  vi.mocked(getMyInvitations)
    .mockReset()
    .mockResolvedValue([
      {
        invitationId: 501,
        status: 'PENDING',
        party: {
          partyId: 77,
          name: '테스트',
          owner: { userId: 7, nickname: '테스트' },
          memberCount: 1,
        },
        createdAt: '2026-09-18T09:00:00Z',
      },
    ]);
  vi.mocked(getParty).mockReset().mockResolvedValue(acceptedParty('ACTIVE'));
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderApp(entries: string[]) {
  return render(
    <AuthContext.Provider value={testAuth}>
      <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
        <Routes>
          <Route path={ROUTE_PATTERN.film} element={<FilmPage />} />
          <Route path={ROUTE_PATTERN.notifications} element={<NotificationListPage />} />
          <Route path={ROUTE_PATTERN.pushPreview} element={<PushPreviewPage />} />
          <Route path={ROUTE_PATTERN.watchConfirm} element={<p>감상 확인</p>} />
          <Route path={ROUTE_PATTERN.partyInvitation} element={<p>파티 초대</p>} />
          <Route path={ROUTE_PATTERN.partyPopcorn} element={<p>파티 상세</p>} />
          <Route path={ROUTE_PATTERN.reportIssued} element={<p>새 리포트 발급</p>} />
          <Route path="*" element={<p>다른 화면</p>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const location = () => screen.getByTestId('location').textContent;

async function findRow(title: string) {
  return (await screen.findByText(title)).closest('button') as HTMLButtonElement;
}

describe('3.1 → 8.1', () => {
  it('미확인 개수를 알림 링크에 표시하고 목록으로 이동한다', async () => {
    renderApp([ROUTE_PATTERN.film]);

    const link = await screen.findByRole('link', { name: '알림, 읽지 않은 알림 4개' });
    fireEvent.click(link);

    expect(location()).toBe('/notifications');
  });

  it('감상 확인·파티 초대·파티 초대 수락·리포트 발급을 최신순 단일 목록에 표시한다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    const list = within(await screen.findByRole('list', { name: '알림 목록' }));
    expect(list.getAllByRole('listitem')).toHaveLength(4);
    expect(list.getByText('취향 리포트 발급')).toBeInTheDocument();
    expect(
      list.getByText('리포트는 MY에서 다시 볼 수 있어요 · 90일 후 자동 삭제'),
    ).toBeInTheDocument();
    expect(list.getByText('영화 잘 보셨나요?')).toBeInTheDocument();
    expect(list.getByText('파티팝콘 초대')).toBeInTheDocument();
    expect(list.getByText('파티팝콘 초대 수락')).toBeInTheDocument();
    expect(list.getByText('새로운 파티팝콘 초대가 도착했어요.')).toBeInTheDocument();
    expect(list.getByText('film_daily 님이 파티팝콘에 참여했어요.')).toBeInTheDocument();
    expect(list.getByText('초대 응답 시 삭제 · 최대 90일 보관')).toBeInTheDocument();
    expect(list.getByText('90일 후 자동 삭제')).toBeInTheDocument();
    expect(list.getByText('감상 확인 시 삭제 · OTT 이동 후 7일까지 보관')).toBeInTheDocument();
  });
});

describe('알림 대상 이동', () => {
  it.each([
    ['영화 잘 보셨나요?', '/watch-records/4004/confirm', 1],
    ['파티팝콘 초대', '/parties/invitations/501', 2],
    ['파티팝콘 초대 수락', '/parties/77/popcorn', 3],
    ['취향 리포트 발급', '/reports/301/new', 4],
  ])('%s 알림을 읽음 처리하고 계약 대상 화면으로 이동한다', async (title, target, id) => {
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow(title));

    await waitFor(() => expect(location()).toBe(target));
    expect(readNotification).toHaveBeenCalledWith(id);
  });
});

/*
 * 업무 -866. 파티 초대 수락 알림은 그 뒤에도 목록에 남는다. 그사이 파티가 끝났거나 내가
 * 나갔으면 5.8로 보내 일반 오류를 보이지 않고, 머문 채 까닭을 말한다.
 */
describe('파티 초대 수락 알림', () => {
  it('참여 중인 파티면 그 파티의 파티원 팝콘(5.8)을 연다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow('파티팝콘 초대 수락'));

    await waitFor(() => expect(location()).toBe('/parties/77/popcorn'));
    // 끝난 파티도 찾아본다. 활성 목록에만 없다고 나간 파티로 볼 수 없다
    expect(getParty).toHaveBeenCalledWith(77, { includeClosed: true });
    if (status === 'ACTIVE') {
      fireEvent.click(screen.getByRole('button', { name: '파티로 이동' }));
      expect(await screen.findByText('파티 상세')).toBeInTheDocument();
    } else {
      expect(screen.queryByRole('button', { name: '파티로 이동' })).not.toBeInTheDocument();
    }
  });

  it('끝난 파티면 이동하지 않고 끝났다고 알린다. 알림은 읽음이 된다', async () => {
    vi.mocked(getParty).mockResolvedValue(acceptedParty('CLOSED'));
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow('파티팝콘 초대 수락'));

    expect(await screen.findByRole('status')).toHaveTextContent('끝난 파티예요');
    expect(location()).toBe('/notifications');
    expect(readNotification).toHaveBeenCalledWith(3);
    expect(within(await findRow('파티팝콘 초대 수락')).queryByText('· 읽지 않음')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    expect(screen.queryByText(/끝난 파티예요/)).not.toBeInTheDocument();
  });

  it('나갔거나 내보내진 파티면 이동하지 않고 그렇다고 알린다', async () => {
    vi.mocked(getParty).mockResolvedValue(null);
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow('파티팝콘 초대 수락'));

    expect(await screen.findByRole('status')).toHaveTextContent('나갔거나 내보내진 파티예요');
    expect(location()).toBe('/notifications');
  });

  it('파티 목록을 못 받으면 가려내지 못한 것이라 이동한다 — 5.8이 제 오류를 보인다', async () => {
    vi.mocked(getParty).mockRejectedValue(new Error('network'));
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow('파티팝콘 초대 수락'));

    await waitFor(() => expect(location()).toBe('/parties/77/popcorn'));
  });

  it('다른 알림은 파티를 찾지 않는다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow('파티팝콘 초대'));

    await waitFor(() => expect(location()).toBe('/parties/invitations/501'));
    expect(getParty).not.toHaveBeenCalled();
  });
});

describe('알림 상태 변경', () => {
  it('전체 읽음 후 현재 목록의 읽지 않음 표시를 모두 지운다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    const readAll = await screen.findByRole('button', { name: '모두 읽음' });
    fireEvent.click(readAll);

    await waitFor(() => expect(readAllNotifications).toHaveBeenCalledOnce());
    expect(screen.queryByText('· 읽지 않음')).not.toBeInTheDocument();
    expect(readAll).toBeDisabled();
  });

  it('한 건 삭제 후 현재 목록에서 제거한다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await screen.findByRole('button', { name: '파티팝콘 초대 수락 알림 삭제' }));

    await waitFor(() => expect(dismissNotification).toHaveBeenCalledWith(3));
    expect(screen.queryByText('파티팝콘 초대 수락')).not.toBeInTheDocument();
  });
});

describe('8.2 예시', () => {
  it('감상 확인 알림을 누르면 감상 기록 화면으로 간다', async () => {
    renderApp([ROUTE_PATTERN.pushPreview]);

    fireEvent.click(await screen.findByRole('button', { name: /영화 잘 보셨나요\?/ }));

    await waitFor(() => expect(location()).toBe('/watch-records/4004/confirm'));
  });
});

describe('이미 응답한 초대 알림', () => {
  beforeEach(() => {
    vi.mocked(getMyInvitations).mockImplementation(async (status) =>
      status === 'ACCEPTED'
        ? [
            {
              invitationId: 501,
              status: 'ACCEPTED',
              createdAt: '2026-09-18T09:00:00Z',
              party: {
                partyId: 77,
                name: '테스트',
                owner: { userId: 7, nickname: '테스트' },
                memberCount: 2,
              },
            },
          ]
        : [],
    );
  });

  it.each([
    ['ACTIVE', '이미 참여 중인 파티예요.'],
    ['CLOSED', '이미 끝난 파티예요.'],
  ] as const)(
    '%s 파티의 수락한 초대를 현재 상태로 안내하고 읽음 처리한다',
    async (status, message) => {
      vi.mocked(getParty).mockResolvedValue(acceptedParty(status));
      renderApp([ROUTE_PATTERN.notifications]);
      fireEvent.click(await findRow(invite.title));
      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(location()).toBe('/notifications');
      expect(readNotification).toHaveBeenCalledWith(invite.notificationId);
      expect(getParty).toHaveBeenCalledWith(77, { includeClosed: true });
    },
  );

  it('이미 수락했어도 현재 멤버가 아니면 참여 중으로 안내하지 않는다', async () => {
    vi.mocked(getParty).mockResolvedValue(null);
    renderApp([ROUTE_PATTERN.notifications]);
    fireEvent.click(await findRow(invite.title));
    expect(await screen.findByText('나갔거나 내보내진 파티예요.')).toBeInTheDocument();
  });

  it('조회 실패를 파티 종료로 안내하지 않는다', async () => {
    vi.mocked(getMyInvitations).mockRejectedValue(new Error('network'));
    renderApp([ROUTE_PATTERN.notifications]);
    fireEvent.click(await findRow(invite.title));
    expect(
      await screen.findByText('초대 상태를 확인하지 못했어요. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument();
  });
});

it('SSE 갱신 신호로 열린 알림 목록을 다시 조회한다', async () => {
  vi.mocked(getNotifications).mockResolvedValueOnce([]).mockResolvedValue([invite]);
  renderApp([ROUTE_PATTERN.notifications]);
  await waitFor(() => expect(getNotifications).toHaveBeenCalledTimes(1));
  notifyNotificationChange();
  expect(await findRow('파티팝콘 초대')).toBeInTheDocument();
  expect(getNotifications).toHaveBeenLastCalledWith({ fresh: true });
});

it('SSE 재조회 전에 시작한 응답이 최신 목록을 덮어쓰지 않는다', async () => {
  let resolve!: (items: NotificationItem[]) => void;
  vi.mocked(getNotifications)
    .mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    )
    .mockResolvedValue([invite]);
  renderApp([ROUTE_PATTERN.notifications]);
  notifyNotificationChange();
  expect(await findRow('파티팝콘 초대')).toBeInTheDocument();
  resolve([]);
  await waitFor(() =>
    expect(screen.getByText('새로운 파티팝콘 초대가 도착했어요.')).toBeInTheDocument(),
  );
});
