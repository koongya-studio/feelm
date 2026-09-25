/*
 * 설정 — 이동 경로 (업무 S15P21E106-307)
 *
 * 이동표 7.8 · 7.8-A ~ 7.8-D의 화살표를 실제 라우트 패턴으로 건 화면들 위에서 밟는다.
 * 값이 남는 흐름(공개 범위 · 알림 · 차단)은 **나갔다 돌아와서** 값이 남는지 본다.
 * 쓰기만 하고 다시 읽는 쪽을 확인하지 않는 것이 가장 자주 낸 실수다.
 *
 * - `api/users`의 API-014와 `api/taste`의 API-042만 가로챈다 — 실제 호출이다.
 *   7.2-B가 쓰는 공개 프로필 목업은 그대로 둔다
 * - 알림 설정과 차단도 실제 호출이 됐다(업무 -394). `fetch`를 가로채 **서버 쪽 상태를
 *   들고 있는 가짜**를 둔다. `api/settings`를 통째로 대신하면 계약의 셋만 나가는지,
 *   나머지 둘이 세션 저장소에 남는지를 못 보게 된다
 * - 로그아웃은 실제 세션 기능이라 Provider 대신 가짜 `signOut`을 넣어 불렸는지 본다
 */
import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import SettingsPage from './index';
import AccountManagePage from '../AccountManagePage';
import BlockedUsersPage from '../BlockedUsersPage';
import MyDashboardPage from '../MyDashboardPage';
import NotificationSettingsPage from '../NotificationSettingsPage';
import PasswordChangePage from '../PasswordChangePage';
import PrivacySettingsPage from '../PrivacySettingsPage';
import PublicProfilePage from '../PublicProfilePage';
import WithdrawPage from '../WithdrawPage';
import { resetSessionForTest, startSession } from '../../../api/session';
import { getMyProfile, getMyProfileView } from '../../../api/users';
import { AuthContext } from '../../../contexts/authContext';
import { resetSessionStore } from '../../../mocks/sessionStore';
import { mockMyProfileView } from '../../../mocks/publicProfiles';
import { mockMyProfile } from '../../../mocks/user';
import { ROUTE_PATTERN } from '../../../routes/paths';
import { resetViewport, setViewportWidth } from '../../../test/viewport';
import { RECOMMENDATIONS_GUIDE, completeGuide, hasSeenGuide } from '../../../utils/userGuides';
import type { AuthValue } from '../../../contexts/authContext';
import type { BlockedUser, NotificationSettings } from '../../../types/user';

vi.mock('../../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/taste')>()),
  getMyFrames: vi.fn(async () => ({ totalCount: 0, items: [], nextCursor: null, hasNext: false })),
}));

vi.mock('../../../api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/users')>()),
  getMyProfile: vi.fn(),
  // 7.1 프로필 머리(업무 -616). 모듈 안에서 부르는 `getMyProfile`은 가로채지 못해 따로 둔다
  getMyProfileView: vi.fn(),
}));

/*
 * 소셜 본인 확인은 제공자 사이트로 나가는 전체 이동이라 가로챈다(업무 -754). 설정 여부는
 * 빌드 변수(client-id)에 달려 있어 시험마다 정한다.
 */
vi.mock('../../../utils/oauth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../utils/oauth')>()),
  startOAuth: vi.fn(),
  isOAuthConfigured: vi.fn(() => true),
}));

const { startOAuth, isOAuthConfigured } = await import('../../../utils/oauth');

const signOut = vi.fn<AuthValue['signOut']>();

/** `beforeEach`가 새로 만든다. 탈퇴 판정에 쓰는 계정 상태를 시험마다 바꾼다 */
let server: ReturnType<typeof createFakeServer>;

/**
 * 알림 설정(API-017 · API-018)과 차단(API-020 ~ API-022)을 들고 있는 가짜 서버.
 *
 * 와이어프레임 7.8-C의 기본값과 7.8-D의 두 사람으로 시작한다. 7.8-D의 기준 목업은
 * 서버가 주는 값과 겹쳐 지웠으므로(`mocks/settings.ts`, 업무 -394) 그 상태를 여기서 만든다.
 */
/** 7.2-B로 여는 무비버디의 공개 식별자와 내부 번호(업무 -810) */
const BUDDY_PUBLIC_ID = '1b4e28ba-2fa1-11d2-883f-0016d3cca427';
const BUDDY_ID = 2048;

function createFakeServer() {
  const notification: NotificationSettings = {
    RATING: true,
    RECOMMENDATION: true,
    PARTY_INVITE: true,
    REPORT_ISSUED: true,
    MARKETING: false,
  };
  /** API-013이 대조하는 계정. `getMyProfile`의 `hasPassword`와 짝을 맞춰 쓴다 */
  const account = { password: 'p@ssw0rd!', hasPassword: true, withdrawn: false };
  /**
   * 소셜 재인증에서 이 계정에 연결된 것으로 볼 인가 코드와, 받은 코드 전부. 같은 코드가 두 번
   * 오면 두 번 적힌다 — 화면이 1회용 코드를 다시 보냈는지 여기서 본다
   */
  const social = { linkedCode: 'code-linked', received: [] as string[] };
  /** API-014 · 015. 7.8-B가 프로필 공개 여부를 여기에 저장한다(업무 -610) */
  const profile = { ...mockMyProfile };
  const blocks: BlockedUser[] = [
    { userId: 5201, nickname: '스포일러왕', blockedAt: '2026-08-30T12:00:00Z' },
    { userId: 5202, nickname: '밤샘시네필', blockedAt: '2026-08-21T12:00:00Z' },
  ];

  function json(status: number, body?: unknown): Response {
    return body === undefined
      ? new Response(null, { status })
      : new Response(JSON.stringify(body), {
          status,
          headers: { 'Content-Type': 'application/json' },
        });
  }

  const BLOCK_PATH = /^\/api\/v1\/users\/(\d+)\/block$/;

  function handle(rawUrl: string, init: RequestInit = {}): Response {
    const url = new URL(rawUrl, 'http://test');
    const method = init.method ?? 'GET';

    /*
     * 비밀번호가 틀리면 API-013은 401이고, 공통 클라이언트는 코드를 가리지 않고 401에
     * 재발급을 한 번 시도한다(`api/client.ts`). 재발급이 성공해도 같은 비밀번호를 다시
     * 보내 또 401이 되는 것이 정상 경로라, 가짜 서버도 그 왕복을 그대로 재현한다.
     */
    if (url.pathname === '/api/v1/auth/token/refresh') {
      return json(200, { accessToken: 'refreshed' });
    }

    if (url.pathname === '/api/v1/users/me' && method === 'PATCH') {
      Object.assign(profile, JSON.parse(init.body as string));

      return json(200, profile);
    }

    if (url.pathname === '/api/v1/users/me' && method === 'DELETE') {
      const body = JSON.parse(init.body as string) as {
        password?: string;
        provider?: string;
        code?: string;
        redirectUri?: string;
      };

      // 소셜 재인증 경로. `provider`가 있으면 비밀번호는 읽지 않는다(API-013, 업무 -435)
      if (body.provider) {
        if (!body.code || !body.redirectUri) {
          return json(400, { code: 'VALIDATION_FAILED', message: '요청이 올바르지 않습니다.' });
        }

        // 인가 코드는 1회용이다. 같은 코드가 두 번 오면 제공자 교환이 실패한다
        const reused = social.received.includes(body.code);

        social.received.push(body.code);

        if (reused) {
          return json(400, {
            code: 'OAUTH_CODE_INVALID',
            message: '인가 코드가 유효하지 않습니다.',
          });
        }

        if (body.code !== social.linkedCode) {
          return json(401, { code: 'INVALID_CREDENTIALS', message: '본인 확인에 실패했습니다.' });
        }

        account.withdrawn = true;

        return json(204);
      }

      // 판정 순서는 계약 그대로다 — 비밀번호 보유 → 값 유무 → 대조(API-013)
      if (!account.hasPassword) {
        return json(409, {
          code: 'PASSWORD_NOT_SET',
          message: '비밀번호가 설정되지 않은 계정입니다.',
        });
      }

      if (!body.password) {
        return json(400, { code: 'VALIDATION_FAILED', message: '비밀번호를 입력해 주세요.' });
      }

      if (body.password !== account.password) {
        return json(401, { code: 'INVALID_CREDENTIALS', message: '비밀번호가 일치하지 않습니다.' });
      }

      account.withdrawn = true;

      return json(204);
    }

    if (url.pathname === '/api/v1/users/me/notification-settings') {
      if (method === 'PUT') {
        // 전체 교체다(API-018). 화면이 보낸 다섯 키가 그대로 다음 조회에 나온다
        Object.assign(notification, JSON.parse(init.body as string));
      }

      return json(200, notification);
    }

    // 7.2-B 공개 프로필(API-062). 무비버디 한 명만 안다(업무 -810)
    if (url.pathname === `/api/v1/users/${BUDDY_PUBLIC_ID}/profile`) {
      return json(200, {
        user: { userId: BUDDY_ID, nickname: '무비버디', avatarUrl: null, visibility: 'PUBLIC' },
        keywords: [],
        stats: { frameCount: 3, popcornCount: 3 },
        popcorn: { flavors: [] },
        recentFrames: [],
        publicReviewCount: 0,
        isBlockedByMe: false,
      });
    }

    if (url.pathname === '/api/v1/users/me/blocks') {
      // 최근에 차단한 사람이 위다(API-022)
      const items = [...blocks].sort((a, b) => b.blockedAt.localeCompare(a.blockedAt));

      return json(200, { items, nextCursor: null, hasNext: false });
    }

    const target = BLOCK_PATH.exec(url.pathname);

    if (target) {
      const userId = Number(target[1]);
      const index = blocks.findIndex((user) => user.userId === userId);

      if (method === 'DELETE') {
        // 멱등이다. 차단 관계가 없어도 204다(API-021)
        if (index !== -1) blocks.splice(index, 1);

        return json(204);
      }

      if (index !== -1) {
        return json(409, { code: 'ALREADY_BLOCKED', message: '이미 차단한 사용자입니다.' });
      }

      // 7.2-B에서 오는 차단(업무 -810). 이 가짜 서버가 아는 사람은 무비버디뿐이다
      if (userId !== BUDDY_ID) {
        return json(404, { code: 'RESOURCE_NOT_FOUND', message: '사용자를 찾을 수 없습니다.' });
      }

      const blockedAt = '2026-09-23T00:00:00Z';
      blocks.push({ userId, nickname: '무비버디', blockedAt });

      return json(201, { blockedUserId: userId, blockedAt });
    }

    throw new Error(`가짜 서버가 모르는 요청이다: ${method} ${url.pathname}`);
  }

  return { handle, account, social, profile };
}

beforeEach(() => {
  resetSessionStore();
  resetSessionForTest();
  // 회원 API라 토큰이 있어야 나간다. 없으면 공통 클라이언트가 재발급부터 시도한다
  startSession('token');

  server = createFakeServer();

  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => Promise.resolve(server.handle(url, init))),
  );

  vi.mocked(getMyProfile)
    .mockReset()
    .mockImplementation(() => Promise.resolve({ ...server.profile }));
  vi.mocked(getMyProfileView)
    .mockReset()
    .mockImplementation(() =>
      Promise.resolve({ ...mockMyProfileView, nickname: server.profile.nickname }),
    );
  signOut.mockReset().mockResolvedValue(undefined);
  vi.mocked(startOAuth).mockReset();
  vi.mocked(isOAuthConfigured).mockReset().mockReturnValue(true);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

/**
 * 항목은 주소이거나, 라우터 state를 함께 실은 이동이다(소셜 본인 확인에서 돌아온 7.8-A-1).
 * `strict`면 StrictMode로 감싸 효과를 두 번 돌린다 — 1회용 코드를 두 번 보내지 않는지 본다.
 */
function renderApp(
  entries: (string | { pathname: string; state: unknown })[],
  { strict = false }: { strict?: boolean } = {},
) {
  const auth: AuthValue = {
    status: 'authenticated',
    user: { userId: 1024, nickname: '김난다', onboardingCompleted: true },
    signIn: vi.fn(),
    signInWithSocial: vi.fn(),
    completeEmailSignup: vi.fn(),
    completeSocialSignup: vi.fn(),
    signOut,
  };

  const tree = (
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={entries} initialIndex={entries.length - 1}>
        <Routes>
          <Route path={ROUTE_PATTERN.my} element={<MyDashboardPage />} />
          <Route path={ROUTE_PATTERN.settings} element={<SettingsPage />} />
          <Route path={ROUTE_PATTERN.accountManage} element={<AccountManagePage />} />
          <Route path={ROUTE_PATTERN.passwordChange} element={<PasswordChangePage />} />
          <Route path={ROUTE_PATTERN.withdraw} element={<WithdrawPage />} />
          <Route path={ROUTE_PATTERN.privacySettings} element={<PrivacySettingsPage />} />
          <Route path={ROUTE_PATTERN.notificationSettings} element={<NotificationSettingsPage />} />
          <Route path={ROUTE_PATTERN.blockedUsers} element={<BlockedUsersPage />} />
          <Route path={ROUTE_PATTERN.publicProfile} element={<PublicProfilePage />} />
          <Route path="*" element={<p>다른 화면</p>} />
        </Routes>
        <LocationProbe />
      </MemoryRouter>
    </AuthContext.Provider>
  );

  return render(strict ? <StrictMode>{tree}</StrictMode> : tree);
}

const location = () => screen.getByTestId('location').textContent;
const back = () => fireEvent.click(screen.getByRole('button', { name: '뒤로 가기' }));

describe('설정 첫 화면의 뒤로 가기', () => {
  it('데스크톱에서는 숨기고 모바일에서는 유지한다', () => {
    setViewportWidth(1440);
    const desktop = renderApp([ROUTE_PATTERN.settings]);
    expect(screen.queryByRole('button', { name: '뒤로 가기' })).not.toBeInTheDocument();

    desktop.unmount();
    resetViewport();
    renderApp([ROUTE_PATTERN.settings]);
    expect(screen.getByRole('button', { name: '뒤로 가기' })).toBeInTheDocument();
  });
});

describe('7.8 → 6.1', () => {
  it('OTT 관리가 설정 안에 있다', async () => {
    /*
     * 7.1 MY 헤더에 있던 줄을 설정 `계정` 묶음으로 옮겼다(사용자 지시 2026-09-16).
     * **7.8이 6.1의 유일한 입구다.**
     */
    renderApp([ROUTE_PATTERN.settings]);

    expect(await screen.findByRole('link', { name: /OTT 관리/ })).toHaveAttribute('href', '/otts');
  });
});

describe('앱 사용법 다시 보기', () => {
  it('완료한 계정의 안내를 추천 단계로 되돌리고 추천 화면으로 이동한다', async () => {
    completeGuide(1024, RECOMMENDATIONS_GUIDE);
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(await screen.findByRole('button', { name: '앱 사용법 다시 보기' }));
    expect(hasSeenGuide(1024, RECOMMENDATIONS_GUIDE)).toBe(false);
    await waitFor(() => expect(location()).toBe('/recommendations'));
  });
});

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

  /* 같이 있던 `아이디 찾기` 줄은 없앴다(CR-25, 업무 -455) */
  it('7.8-A의 비밀번호 복구는 0.7로 가고 아이디 찾기 줄은 없다', async () => {
    renderApp([ROUTE_PATTERN.accountManage]);

    expect(await screen.findByRole('link', { name: /비밀번호 복구/ })).toHaveAttribute(
      'href',
      '/account/find',
    );
    expect(screen.queryByRole('link', { name: /아이디 찾기/ })).toBeNull();
  });

  it('소셜 전용 계정에는 비밀번호 변경이 없다', async () => {
    // API-012가 비밀번호 없는 계정에 409 PASSWORD_NOT_SET을 준다
    vi.mocked(getMyProfile).mockResolvedValue({ ...mockMyProfile, hasPassword: false });
    renderApp([ROUTE_PATTERN.accountManage]);

    await screen.findByRole('link', { name: /비밀번호 복구/ });
    expect(screen.queryByRole('link', { name: /비밀번호 변경/ })).toBeNull();
  });

  /* 7.8-A-2는 와이어프레임에 없이 새로 만든 화면이다(CR-22, 업무 -391) */
  it('7.8-A의 비밀번호 변경은 7.8-A-2로 간다', async () => {
    renderApp([ROUTE_PATTERN.accountManage]);

    fireEvent.click(await screen.findByRole('link', { name: /비밀번호 변경/ }));

    expect(location()).toBe('/my/settings/account/password');
    expect(screen.getByLabelText('현재 비밀번호')).toBeInTheDocument();
  });
});

describe('7.8-A-1 회원 탈퇴', () => {
  /** 체크 · 비밀번호를 채워 `탈퇴하기`가 열린 상태까지 간다 */
  async function fillWithdrawForm(password: string) {
    const withdraw = await screen.findByRole('button', { name: '탈퇴하기' });

    fireEvent.click(screen.getByLabelText('위 내용을 모두 확인했어요'));
    fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: password } });

    return withdraw;
  }

  it('확인 체크와 비밀번호 전에는 탈퇴할 수 없고, 한 번 더 확인한 뒤 세션을 끊고 시작 화면으로 간다', async () => {
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.withdraw]);

    const withdraw = await screen.findByRole('button', { name: '탈퇴하기' });
    expect(withdraw).toBeDisabled();

    // 체크만으로는 열리지 않는다. API-013이 비밀번호를 받는다(CR-24)
    fireEvent.click(screen.getByLabelText('위 내용을 모두 확인했어요'));
    expect(withdraw).toBeDisabled();

    fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: 'p@ssw0rd!' } });
    expect(withdraw).toBeEnabled();

    fireEvent.click(withdraw);

    const dialog = screen.getByRole('alertdialog', { name: 'Feelm을 탈퇴할까요?' });
    // 확인 창이 뜨면 초점은 되돌릴 수 있는 쪽에 있다
    expect(within(dialog).getByRole('button', { name: '취소' })).toHaveFocus();

    fireEvent.click(within(dialog).getByRole('button', { name: '탈퇴' }));

    await waitFor(() => expect(location()).toBe('/start'));
    expect(signOut).toHaveBeenCalledTimes(1);
    expect(server.account.withdrawn).toBe(true);
  });

  it('비밀번호가 틀리면 그 사실을 알리고 계정은 그대로 남는다', async () => {
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.withdraw]);

    const withdraw = await fillWithdrawForm('wrong-password');
    fireEvent.click(withdraw);
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '탈퇴' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호가 올바르지 않아요.');
    // 확인 창은 닫히고 화면에 남아 다시 적을 수 있다
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(location()).toBe('/my/settings/account/withdraw');
    expect(server.account.withdrawn).toBe(false);
    expect(signOut).not.toHaveBeenCalled();
  });

  /*
   * 소셜 전용 계정은 연결된 제공자로 본인을 확인한다(API-013 소셜 재인증, 업무 -435 · -754).
   * 제공자 로그인은 다른 사이트로 나갔다 `/oauth/callback`으로 돌아오는 전체 이동이라, 콜백이
   * 인가 코드를 라우터 state(`reauth`)로 넘긴 7.8-A-1부터 이어 본다.
   */
  const socialOnly = { ...mockMyProfile, hasPassword: false, linkedProviders: ['GOOGLE'] };
  const returned = (reauth: unknown) => ({ pathname: ROUTE_PATTERN.withdraw, state: { reauth } });
  const REDIRECT = 'http://localhost:5173/app/oauth/callback';

  it('소셜 전용 계정은 비밀번호 칸 대신 제공자 안내가 서고, 확인하면 제공자 로그인으로 나간다', async () => {
    vi.mocked(getMyProfile).mockResolvedValue(socialOnly);
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.withdraw]);

    expect(await screen.findByText(/구글 계정으로 가입해 비밀번호가 없어요/)).toBeInTheDocument();
    expect(screen.queryByLabelText('현재 비밀번호')).toBeNull();

    const withdraw = screen.getByRole('button', { name: '탈퇴하기' });
    expect(withdraw).toBeDisabled();

    fireEvent.click(screen.getByLabelText('위 내용을 모두 확인했어요'));
    fireEvent.click(withdraw);

    const dialog = screen.getByRole('alertdialog', { name: 'Feelm을 탈퇴할까요?' });
    expect(dialog).toHaveTextContent('구글에서 본인을 확인하면');
    fireEvent.click(within(dialog).getByRole('button', { name: '탈퇴' }));

    expect(startOAuth).toHaveBeenCalledWith('google', 'withdraw');
    // 탈퇴 요청은 제공자에서 코드를 받아 온 뒤에야 나간다
    expect(server.account.withdrawn).toBe(false);
  });

  it('본인 확인에서 코드를 들고 돌아오면 확인 상태를 되살려 한 번만 탈퇴하고 시작 화면으로 간다', async () => {
    vi.mocked(getMyProfile).mockResolvedValue(socialOnly);
    // StrictMode는 효과를 두 번 돌린다. 그래도 1회용 코드는 한 번만 나가야 한다
    renderApp(
      [
        ROUTE_PATTERN.settings,
        returned({ provider: 'google', code: 'code-linked', redirectUri: REDIRECT }),
      ],
      { strict: true },
    );

    await waitFor(() => expect(location()).toBe(ROUTE_PATTERN.authStart));
    expect(server.account.withdrawn).toBe(true);
    expect(server.social.received).toEqual(['code-linked']);
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('연결되지 않은 소셜 계정으로 확인하면 그렇게 알리고, 같은 코드를 다시 보내지 않는다', async () => {
    vi.mocked(getMyProfile).mockResolvedValue(socialOnly);
    renderApp([
      ROUTE_PATTERN.settings,
      returned({ provider: 'google', code: 'someone-else', redirectUri: REDIRECT }),
    ]);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '지금 로그인한 계정과 연결된 소셜 계정이 아니에요',
    );
    // 401이어도 재발급 뒤 같은 코드를 다시 보내지 않는다 — 두 번째는 OAUTH_CODE_INVALID가 된다
    expect(server.social.received).toEqual(['someone-else']);
    expect(server.account.withdrawn).toBe(false);
    expect(signOut).not.toHaveBeenCalled();

    // 확인 체크는 되살아나 있어 바로 다시 시도할 수 있다
    expect(screen.getByLabelText('위 내용을 모두 확인했어요')).toBeChecked();
    expect(screen.getByRole('button', { name: '탈퇴하기' })).toBeEnabled();
  });

  it('제공자 동의 화면에서 취소하고 돌아오면 탈퇴하지 않았다고 알린다', async () => {
    vi.mocked(getMyProfile).mockResolvedValue(socialOnly);
    renderApp([ROUTE_PATTERN.settings, returned({ cancelled: true })]);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '본인 확인을 취소해 탈퇴하지 않았어요',
    );
    expect(server.social.received).toEqual([]);
    expect(location()).toBe('/my/settings/account/withdraw');
  });

  it('이 앱이 제공자 인가를 시작할 수 없으면 탈퇴하기를 열지 않고 이유를 알린다', async () => {
    // 배포에 소셜 client-id가 아직 없을 때(업무 -531 · -532)
    vi.mocked(isOAuthConfigured).mockReturnValue(false);
    vi.mocked(getMyProfile).mockResolvedValue(socialOnly);
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.withdraw]);

    expect(await screen.findByText(/지금은 확인을 쓸 수 없어요/)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('위 내용을 모두 확인했어요'));
    expect(screen.getByRole('button', { name: '탈퇴하기' })).toBeDisabled();
  });

  it('네이버만 연결된 소셜 계정에는 재인증 진입점을 열지 않는다', async () => {
    vi.mocked(getMyProfile).mockResolvedValue({
      ...socialOnly,
      linkedProviders: ['NAVER'],
    });
    renderApp([ROUTE_PATTERN.settings, ROUTE_PATTERN.withdraw]);

    expect(await screen.findByText(/지금은 확인을 쓸 수 없어요/)).toBeInTheDocument();
    expect(screen.queryByText(/네이버/)).toBeNull();
    fireEvent.click(screen.getByLabelText('위 내용을 모두 확인했어요'));
    expect(screen.getByRole('button', { name: '탈퇴하기' })).toBeDisabled();
    expect(startOAuth).not.toHaveBeenCalled();
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
  it('프로필 공개 여부는 API-015로 저장되어 나갔다 들어와도 그대로다', async () => {
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(screen.getByRole('link', { name: /공개 범위 설정/ }));

    const profile = await screen.findByRole('radiogroup', { name: '프로필 공개' });
    expect(within(profile).getByRole('radio', { name: '전체 공개' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    // 계약(API-015)의 단계는 둘이다. `파티원만` 칸은 그리지 않는다
    expect(
      within(profile)
        .getAllByRole('radio')
        .map((radio) => radio.textContent),
    ).toEqual(['전체 공개', '비공개']);

    fireEvent.click(within(profile).getByRole('radio', { name: '비공개' }));
    await waitFor(() => expect(server.profile.visibility).toBe('PRIVATE'));

    back();
    await waitFor(() => expect(location()).toBe('/my/settings'));
    fireEvent.click(screen.getByRole('link', { name: /공개 범위 설정/ }));

    const reopened = await screen.findByRole('radiogroup', { name: '프로필 공개' });
    expect(within(reopened).getByRole('radio', { name: '비공개' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  it('취향 필름 · 팝콘 · 한줄평 공개 범위도 API-015로 저장되어 나갔다 들어와도 그대로다', async () => {
    /*
     * 한때 세션 저장소에만 두어 새로고침하면 사라졌고(업무 -610) 그래서 잠갔다. 업무 -856에서
     * 계약(API-014 · 015의 세 필드)이 생겨 서버에 저장한다
     */
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(screen.getByRole('link', { name: /공개 범위 설정/ }));

    const film = await screen.findByRole('radiogroup', { name: '취향 필름 공개' });
    // 기본값은 모두 공개다
    expect(within(film).getByRole('radio', { name: '전체 공개' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.queryByText(/준비 중/)).not.toBeInTheDocument();

    // 네 줄 모두 `파티원만`은 계약에 없어 칸이 없다(업무 -856 결정 D-1)
    for (const name of ['프로필 공개', '취향 필름 공개', '팝콘 버킷 공개', '한줄평 공개']) {
      const group = screen.getByRole('radiogroup', { name });
      expect(within(group).queryByRole('radio', { name: '파티원만' })).toBeNull();
      expect(within(group).getByRole('radio', { name: '비공개' })).toBeEnabled();
    }

    fireEvent.click(within(film).getByRole('radio', { name: '비공개' }));
    // 저장 중에도 다른 줄은 잠기지 않는다. 요청은 차례로 나간다(업무 -854)
    const review = screen.getByRole('radiogroup', { name: '한줄평 공개' });
    expect(within(review).getByRole('radio', { name: '비공개' })).toBeEnabled();
    fireEvent.click(within(review).getByRole('radio', { name: '비공개' }));

    await waitFor(() => expect(server.profile.frameVisibility).toBe('PRIVATE'));
    await waitFor(() => expect(server.profile.reviewVisibility).toBe('PRIVATE'));

    // 보낸 것은 고른 필드 하나뿐이다. 다른 줄은 건드리지 않는다
    expect(server.profile.popcornVisibility).toBe('PUBLIC');
    expect(server.profile.visibility).toBe('PUBLIC');

    back();
    await waitFor(() => expect(location()).toBe('/my/settings'));
    fireEvent.click(screen.getByRole('link', { name: /공개 범위 설정/ }));

    const reopened = await screen.findByRole('radiogroup', { name: '취향 필름 공개' });
    expect(within(reopened).getByRole('radio', { name: '비공개' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(
      within(screen.getByRole('radiogroup', { name: '한줄평 공개' })).getByRole('radio', {
        name: '비공개',
      }),
    ).toHaveAttribute('aria-checked', 'true');
    expect(
      within(screen.getByRole('radiogroup', { name: '팝콘 버킷 공개' })).getByRole('radio', {
        name: '전체 공개',
      }),
    ).toHaveAttribute('aria-checked', 'true');
  });

  it('공개 범위를 저장하지 못하면 고르기 전 값으로 돌아가고 알린다', async () => {
    renderApp([ROUTE_PATTERN.privacySettings]);

    const popcorn = await screen.findByRole('radiogroup', { name: /팝콘 버킷 공개/ });
    // 팝콘 버킷 줄에는 파티 예외(API-057)를 적는다
    expect(popcorn).toHaveAccessibleDescription(/파티원에게는 파티 화면에서 보여요/);

    vi.mocked(fetch).mockImplementationOnce(() =>
      Promise.resolve(
        new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: '서버 오류' }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    fireEvent.click(within(popcorn).getByRole('radio', { name: '비공개' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('저장하지 못했어요');
    expect(within(popcorn).getByRole('radio', { name: '전체 공개' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(server.profile.popcornVisibility).toBe('PUBLIC');
  });

  it('계약에 있는 알림은 저장되어 나갔다 들어와도 그대로고, 없는 둘은 잠겨 있다', async () => {
    renderApp([ROUTE_PATTERN.settings]);

    fireEvent.click(screen.getByRole('link', { name: /알림 설정/ }));

    // 와이어프레임 7.8-C의 다섯 개. 마케팅만 꺼져 있다
    expect(await screen.findAllByRole('switch')).toHaveLength(5);
    expect(screen.getByRole('switch', { name: '마케팅 알림' })).toHaveAttribute(
      'aria-checked',
      'false',
    );

    // 업무 -610에서 잠갔던 두 줄이 모두 열렸다. 준비 중 안내가 남지 않는다(업무 -857 · -858)
    for (const toggle of screen.getAllByRole('switch')) expect(toggle).toBeEnabled();
    expect(screen.queryByText(/준비 중/)).toBeNull();

    // 리포트 발급도 서버에 저장되고 다시 들어와도 남는다(업무 -858)
    fireEvent.click(screen.getByRole('switch', { name: '리포트 발급 알림' }));
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: '리포트 발급 알림' })).toHaveAttribute(
        'aria-checked',
        'false',
      ),
    );
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: '파티 초대 알림' })).toBeEnabled(),
    );

    // 파티 초대는 계약에 들어와(업무 -857) 서버에 저장되고 다시 들어와도 남는다
    const partyInvite = screen.getByRole('switch', { name: '파티 초대 알림' });
    expect(partyInvite).toBeEnabled();
    expect(partyInvite).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(partyInvite);
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: '파티 초대 알림' })).toHaveAttribute(
        'aria-checked',
        'false',
      ),
    );
    await waitFor(() => expect(screen.getByRole('switch', { name: '마케팅 알림' })).toBeEnabled());

    fireEvent.click(screen.getByRole('switch', { name: '마케팅 알림' }));
    await waitFor(() =>
      expect(screen.getByRole('switch', { name: '마케팅 알림' })).toHaveAttribute(
        'aria-checked',
        'true',
      ),
    );

    back();
    await waitFor(() => expect(location()).toBe('/my/settings'));
    fireEvent.click(screen.getByRole('link', { name: /알림 설정/ }));

    expect(await screen.findByRole('switch', { name: '마케팅 알림' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByRole('switch', { name: '파티 초대 알림' })).toHaveAttribute(
      'aria-checked',
      'false',
    );
    expect(screen.getByRole('switch', { name: '리포트 발급 알림' })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });
});

describe('7.8 → 7.8-D', () => {
  it('7.2-B에서 차단하면 7.8-D에 들어가고, 해제는 7.8-D가 실제 API로 한다', async () => {
    /*
     * 7.2-B가 실제 API-062를 부르게 되어(업무 -810) 목업이던 동안 잠갔던 차단(업무 -610)을
     * 되살렸다. 차단은 확인 창 → API-020 → 7.8-D(`replace`)다.
     */
    renderApp([`/users/${BUDDY_PUBLIC_ID}`]);

    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: '차단' }),
    );

    await waitFor(() => expect(location()).toBe(ROUTE_PATTERN.blockedUsers));
    expect(await screen.findByText('3명 차단 중')).toBeInTheDocument();
    expect(screen.getByText('무비버디')).toBeInTheDocument();

    fireEvent.click(await screen.findByRole('button', { name: '스포일러왕 차단 해제' }));

    expect(await screen.findByText('2명 차단 중')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '스포일러왕 차단 해제' })).toBeNull();
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
