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
  title: '파티 모드 초대',
  body: '새로운 파티 초대가 도착했어요.',
  targetType: 'PARTY_INVITATION',
  targetId: 501,
  readAt: null,
  notifiedAt: '2026-09-17T09:00:00Z',
};

const accepted: NotificationItem = {
  notificationId: 3,
  type: 'PARTY_INVITE_ACCEPTED',
  title: '파티 모드 초대 수락',
  body: 'film_daily 님이 파티 모드에 참여했어요.',
  targetType: 'PARTY',
  targetId: 77,
  readAt: null,
  notifiedAt: '2026-09-18T09:00:00Z',
};

beforeEach(() => {
  vi.mocked(getNotifications).mockReset().mockResolvedValue([accepted, watch, invite]);
  vi.mocked(getLatestPushNotification).mockReset().mockResolvedValue(watch);
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(3);
  vi.mocked(readNotification).mockReset().mockResolvedValue();
  vi.mocked(readAllNotifications).mockReset().mockResolvedValue();
  vi.mocked(dismissNotification).mockReset().mockResolvedValue();
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

    const link = await screen.findByRole('link', { name: '알림, 읽지 않은 알림 3개' });
    fireEvent.click(link);

    expect(location()).toBe('/notifications');
  });

  it('감상 확인·파티 초대·파티 초대 수락을 최신순 단일 목록에 표시한다', async () => {
    renderApp([ROUTE_PATTERN.notifications]);

    const list = within(await screen.findByRole('list', { name: '알림 목록' }));
    expect(list.getAllByRole('listitem')).toHaveLength(3);
    expect(list.getByText('영화 잘 보셨나요?')).toBeInTheDocument();
    expect(list.getByText('파티 모드 초대')).toBeInTheDocument();
    expect(list.getByText('파티 모드 초대 수락')).toBeInTheDocument();
    expect(list.getByText('초대 응답 시 삭제 · 최대 90일 보관')).toBeInTheDocument();
    expect(list.getByText('90일 후 자동 삭제')).toBeInTheDocument();
    expect(list.getByText('감상 확인 시 삭제 · OTT 이동 후 7일까지 보관')).toBeInTheDocument();
  });
});

describe('알림 대상 이동', () => {
  it.each([
    ['영화 잘 보셨나요?', '/watch-records/4004/confirm', 1],
    ['파티 모드 초대', '/parties/invitations/501', 2],
    ['파티 모드 초대 수락', '/parties/77/popcorn', 3],
  ])('%s 알림을 읽음 처리하고 계약 대상 화면으로 이동한다', async (title, target, id) => {
    renderApp([ROUTE_PATTERN.notifications]);

    fireEvent.click(await findRow(title));

    await waitFor(() => expect(location()).toBe(target));
    expect(readNotification).toHaveBeenCalledWith(id);
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

    fireEvent.click(await screen.findByRole('button', { name: '파티 모드 초대 수락 알림 삭제' }));

    await waitFor(() => expect(dismissNotification).toHaveBeenCalledWith(3));
    expect(screen.queryByText('파티 모드 초대 수락')).not.toBeInTheDocument();
  });
});

describe('8.2 예시', () => {
  it('감상 확인 알림을 누르면 감상 기록 화면으로 간다', async () => {
    renderApp([ROUTE_PATTERN.pushPreview]);

    fireEvent.click(await screen.findByRole('button', { name: /영화 잘 보셨나요\?/ }));

    await waitFor(() => expect(location()).toBe('/watch-records/4004/confirm'));
  });
});
