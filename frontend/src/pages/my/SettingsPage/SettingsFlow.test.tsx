/*
 * 설정 — 이동 경로 (업무 S15P21E106-307)
 *
 * 이동표 7.8 · 7.8-A ~ 7.8-D의 화살표를 실제 라우트 패턴으로 건 화면들 위에서 밟는다.
 * 세션 저장소에 쓰는 흐름(공개 범위 · 알림 · 차단)은 **나갔다 돌아와서** 값이 남는지 본다.
 * 쓰기만 하고 다시 읽는 쪽을 확인하지 않는 것이 가장 자주 낸 실수다.
 *
 * - `api/users`의 API-014만 가로챈다 — 실제 호출이다. 7.2-B가 쓰는 공개 프로필 목업은 그대로 둔다
 * - 로그아웃은 실제 세션 기능이라 Provider 대신 가짜 `signOut`을 넣어 불렸는지 본다
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import SettingsPage from './index';
import AccountManagePage from '../AccountManagePage';
import BlockedUsersPage from '../BlockedUsersPage';
import MyDashboardPage from '../MyDashboardPage';
import NotificationSettingsPage from '../NotificationSettingsPage';
import PrivacySettingsPage from '../PrivacySettingsPage';
import PublicProfilePage from '../PublicProfilePage';
import WithdrawPage from '../WithdrawPage';
import { getMyProfile } from '../../../api/users';
import { AuthContext } from '../../../contexts/authContext';
import { resetSessionStore } from '../../../mocks/sessionStore';
import { mockMyProfile } from '../../../mocks/user';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { AuthValue } from '../../../contexts/authContext';

vi.mock('../../../api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/users')>()),
  getMyProfile: vi.fn(),
}));

const signOut = vi.fn<AuthValue['signOut']>();

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  signOut.mockReset().mockResolvedValue(undefined);
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderApp(entries: string[]) {
  const auth: AuthValue = {
    status: 'authenticated',
    user: { userId: 1024, nickname: '김난다', onboardingCompleted: true },
    signIn: vi.fn(),
    signOut,
  };

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
        <Routes>
          <Route path={ROUTE_PATTERN.my} element={<MyDashboardPage />} />
          <Route path={ROUTE_PATTERN.settings} element={<SettingsPage />} />
          <Route path={ROUTE_PATTERN.accountManage} element={<AccountManagePage />} />
          <Route path={ROUTE_PATTERN.withdraw} element={<WithdrawPage />} />
          <Route path={ROUTE_PATTERN.privacySettings} element={<PrivacySettingsPage />} />
          <Route path={ROUTE_PATTERN.notificationSettings} element={<NotificationSettingsPage />} />
          <Route path={ROUTE_PATTERN.blockedUsers} element={<BlockedUsersPage />} />
          <Route path={ROUTE_PATTERN.publicProfile} element={<PublicProfilePage />} />
          <Route path="*" element={<p>다른 화면</p>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const location = () => screen.getByTestId('location').textContent;
const back = () => fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }));

describe('7.1 → 7.8 → 7.8-A → 7.8-A-1', () => {
  it('회원 탈퇴 확인에서 계속 이용하기를 누르면 계정 관리로 돌아온다', async () => {
    renderApp([ROUTE_PATTERN.my]);

    fireEvent.click(await screen.findByRole('link', { name: '설정' }));
    expect(location()).toBe('/my/settings');

    fireEvent.click(screen.getByRole('link', { name: /계정 관리/ }));
    expect(location()).toBe('/my/settings/account');
    // 헤더 아래와 이메일 줄에 API-014의 이메일이 나온다
    expect(await screen.findAllByText('nanda@feelm.kr')).toHaveLength(2);

    fireEvent.click(screen.getByRole('link', { name: /회원 탈퇴/ }));
    expect(location()).toBe('/my/settings/account/withdraw');

    // 사라지는 수치는 API-014 stats에서 온다
    expect(
      await screen.findByText('취향 필름 100프레임과 팝콘 100알이 모두 삭제돼요'),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '계속 이용하기' }));
    await waitFor(() => expect(location()).toBe('/my/settings/account'));
  });

  it('7.8-A의 아이디 찾기와 비밀번호 복구는 0.7로 간다', async () => {
    renderApp([ROUTE_PATTERN.accountManage]);

    expect(await screen.findByRole('link', { name: /아이디 찾기/ })).toHaveAttribute(
      'href',
      '/account/find',
    );
    expect(screen.getByRole('link', { name: /비밀번호 복구/ })).toHaveAttribute(
      'href',
      '/account/find',
    );
  });

  it('소셜 전용 계정에는 비밀번호 변경이 없다', async () => {
    // API-012가 비밀번호 없는 계정에 409 PASSWORD_NOT_SET을 준다
    vi.mocked(getMyProfile).mockResolvedValue({ ...mockMyProfile, hasPassword: false });
    renderApp([ROUTE_PATTERN.accountManage]);

    await screen.findByRole('link', { name: /아이디 찾기/ });
    expect(screen.queryByRole('button', { name: /비밀번호 변경/ })).toBeNull();
  });
});

describe('7.8-A-1 회원 탈퇴', () => {
  it('확인 체크 전에는 탈퇴할 수 없고, 한 번 더 확인한 뒤 세션을 끊고 시작 화면으로 간다', async () => {
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.withdraw]);

    const withdraw = await screen.findByRole('button', { name: '탈퇴하기' });
    expect(withdraw).toBeDisabled();

    fireEvent.click(screen.getByLabelText('위 내용을 모두 확인했어요'));
    fireEvent.click(withdraw);

    const dialog = screen.getByRole('alertdialog', { name: 'Feelm을 탈퇴할까요?' });
    // 확인 창이 뜨면 초점은 되돌릴 수 있는 쪽에 있다
    expect(within(dialog).getByRole('button', { name: '취소' })).toHaveFocus();

    fireEvent.click(within(dialog).getByRole('button', { name: '탈퇴' }));

    await waitFor(() => expect(location()).toBe('/start'));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('7.8 맨 아래 회원 탈퇴도 같은 확인 화면으로 간다', async () => {
    renderApp([ROUTE_PATTERN.settings]);

    expect(screen.getByRole('link', { name: '회원 탈퇴' })).toHaveAttribute(
      'href',
      '/my/settings/account/withdraw',
    );
    // 이동표에는 없지만 와이어프레임 7.8에 줄이 있다
    expect(screen.getByRole('link', { name: /프로필 편집/ })).toHaveAttribute(
      'href',
      '/my/profile/edit',
    );
  });
});

describe('7.8 ⇢ 0.6 로그아웃', () => {
  it('세션을 끊고 로그인 화면으로 간다', async () => {
    renderApp([ROUTE_PATTERN.my, ROUTE_PATTERN.settings]);

    fireEvent.click(screen.getByRole('button', { name: /로그아웃/ }));

    await waitFor(() => expect(location()).toBe('/login'));
    expect(signOut).toHaveBeenCalledTimes(1);
  });
});

describe('7.8 → 7.8-B · 7.8-C', () => {
  it('공개 범위를 바꾸고 나갔다 들어와도 그대로다', async () => {
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(screen.getByRole('link', { name: /공개 범위 설정/ }));

    const film = await screen.findByRole('radiogroup', { name: '취향 필름 공개' });
    // 와이어프레임 7.8-B는 취향 필름만 파티원만이다
    expect(within(film).getByRole('radio', { name: '파티원만' })).toHaveAttribute(
      'aria-checked',
      'true',
    );

    fireEvent.click(within(film).getByRole('radio', { name: '비공개' }));

    back();
    await waitFor(() => expect(location()).toBe('/my/settings'));
    fireEvent.click(screen.getByRole('link', { name: /공개 범위 설정/ }));

    const reopened = await screen.findByRole('radiogroup', { name: '취향 필름 공개' });
    expect(within(reopened).getByRole('radio', { name: '비공개' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  it('알림을 끄고 나갔다 들어와도 그대로다', async () => {
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(screen.getByRole('link', { name: /알림 설정/ }));

    // 와이어프레임 7.8-C의 다섯 개. 마케팅만 꺼져 있다
    expect(await screen.findAllByRole('switch')).toHaveLength(5);
    expect(screen.getByRole('switch', { name: '마케팅 알림' })).toHaveAttribute(
      'aria-checked',
      'false',
    );

    fireEvent.click(screen.getByRole('switch', { name: '파티 초대 알림' }));
    expect(screen.getByRole('switch', { name: '파티 초대 알림' })).toHaveAttribute(
      'aria-checked',
      'false',
    );

    back();
    await waitFor(() => expect(location()).toBe('/my/settings'));
    fireEvent.click(screen.getByRole('link', { name: /알림 설정/ }));

    expect(await screen.findByRole('switch', { name: '파티 초대 알림' })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });
});

describe('7.2-B ⇢ 7.8-D · 7.8 → 7.8-D', () => {
  it('차단한 사람이 목록에 생기고 프로필이 감춰지며, 해제하면 다시 보인다', async () => {
    const { unmount } = renderApp(['/users/2048']);

    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '차단' }));

    await waitFor(() => expect(location()).toBe('/my/settings/blocks'));
    expect(await screen.findByText('3명 차단 중')).toBeInTheDocument();
    expect(screen.getByText('무비버디')).toBeInTheDocument();
    unmount();

    // 차단한 사람의 프로필은 보이지 않는다(API-020)
    const profile = renderApp(['/users/2048']);
    expect(await screen.findByText('프로필을 불러오지 못했어요')).toBeInTheDocument();
    profile.unmount();

    // 7.8 → 7.8-D에서 해제한다
    renderApp([ROUTE_PATTERN.settings]);
    fireEvent.click(screen.getByRole('link', { name: /차단 사용자 관리/ }));
    fireEvent.click(await screen.findByRole('button', { name: '무비버디 차단 해제' }));

    expect(await screen.findByText('2명 차단 중')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '무비버디 차단 해제' })).toBeNull();
  });

  it('기준 목업의 사람도 해제되고 다시 들어와도 빠져 있다', async () => {
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.blockedUsers]);

    fireEvent.click(await screen.findByRole('button', { name: '스포일러왕 차단 해제' }));
    expect(await screen.findByText('1명 차단 중')).toBeInTheDocument();

    back();
    await waitFor(() => expect(location()).toBe('/my/settings'));
    fireEvent.click(screen.getByRole('link', { name: /차단 사용자 관리/ }));

    expect(await screen.findByText('1명 차단 중')).toBeInTheDocument();
    expect(screen.queryByText('스포일러왕')).toBeNull();
  });
});
