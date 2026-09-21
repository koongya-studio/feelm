/*
 * 0.6 로그인 — 이동 분기와 실패 안내 (업무 S15P21E106-302 · S15P21E106-364)
 *
 * 와이어프레임 이동표는 로그인 뒤 가는 곳을 두 갈래로 나눈다.
 * 0.6 ⇢ 1.1 신규 사용자가 처음 로그인한 경우
 * 0.6 → 2.1 취향 설정을 완료한 기존 사용자 로그인
 *
 * 무엇으로 갈라지는지가 와이어프레임에 적혀 있지 않아 `onboardingCompleted`로 정했고,
 * 연동(-364) 뒤에는 목업이 아니라 로그인 응답이 그 값을 준다(API-004). 화면에서 정한
 * 규칙이라 회귀가 나기 쉬우므로 두 갈래를 모두 고정한다.
 *
 * 실패 세 가지도 함께 본다. 같은 401·403·429라도 사용자가 다음에 할 일이 다르고,
 * 미인증 계정만 인증 메일 재발송(API-003)을 내보내야 한다.
 *
 * 그 밖의 실패는 다른 화면과 같은 기준으로 원인을 말한다(업무 -374). 서버에 닿지 못한
 * 것과 서버가 처리하지 못한 것을 가르고, 그 밖의 거절은 서버 문구를 그대로 쓴다.
 *
 * 도움말 줄도 함께 본다. `비밀번호 찾기`는 0.7로 가고, 같이 있던 `아이디 찾기`는
 * 없앴다(CR-25, 업무 S15P21E106-455). 지운 링크는 소리 없이 되살아나기 쉽다.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import LoginPage from './index';
import { ApiError, NETWORK_ERROR } from '../../../api/apiError';
import {
  NETWORK_FAILURE_DESCRIPTION,
  SERVER_FAILURE_DESCRIPTION,
} from '../../../components/StatusView/failure';
import { AuthProvider } from '../../../contexts/AuthProvider';
import { renderWithRouter } from '../../../test/render';

vi.mock('../../../api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  restoreSession: vi.fn(),
}));

const { login, restoreSession } = await import('../../../api/auth');

const ACTIVE_USER = { userId: 1, nickname: '팝콘러버', onboardingCompleted: true };
const NEW_USER = { ...ACTIVE_USER, onboardingCompleted: false };

beforeEach(() => {
  vi.mocked(login).mockReset();
  // 로그인하지 않은 상태로 화면을 연다
  vi.mocked(restoreSession).mockReset().mockResolvedValue(null);
});

function renderLogin() {
  return renderWithRouter(
    <AuthProvider>
      <LoginPage />
    </AuthProvider>,
    { route: '/login' },
  );
}

/**
 * 세션이 만료돼 끌려온 상태로 연다(업무 -596).
 *
 * `renderWithRouter`는 주소만 받으므로 이동에 실린 상태까지 주려면 여기서 감싼다.
 */
function renderExpiredLogin() {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: { sessionExpired: true } }]}>
      <AuthProvider>
        <LoginPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function click(name: string) {
  fireEvent.click(screen.getByRole('button', { name }));
}

/** 계정 도움말 줄은 이동이므로 링크다 */
function follow(name: string) {
  fireEvent.click(screen.getByRole('link', { name }));
}

/** 이동 결과. 부분 일치로 보면 `/`가 모든 경로에 걸리므로 전체를 비교한다 */
function location() {
  return screen.getByTestId('location').textContent;
}

function fillCredentials(email = 'user@example.com') {
  fireEvent.change(screen.getByLabelText('아이디 (이메일)'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'p@ssw0rd!' } });
}

function rejectWith(status: number, code: string) {
  vi.mocked(login).mockRejectedValue(new ApiError(status, code, '서버가 준 문구'));
}

describe('0.6 로그인 — 세션 만료 안내 (업무 S15P21E106-596)', () => {
  it('세션이 만료돼 끌려온 경우에는 이유를 알려 준다', () => {
    renderExpiredLogin();

    expect(screen.getByRole('status')).toHaveTextContent('다시 로그인해 주세요');
  });

  it('스스로 연 로그인 화면에는 만료 안내가 없다', () => {
    renderLogin();

    expect(screen.queryByText(/다시 로그인해 주세요/)).toBeNull();
  });
});

describe('0.6 로그인', () => {
  it('취향 설정을 마친 사용자는 필름으로 간다', async () => {
    vi.mocked(login).mockResolvedValue(ACTIVE_USER);
    renderLogin();

    fillCredentials();
    click('로그인');

    await waitFor(() => {
      expect(location()).toBe('/film');
    });
    expect(login).toHaveBeenCalledWith('user@example.com', 'p@ssw0rd!');
  });

  it('취향 설정을 마치지 않은 사용자는 초기 취향 설정으로 간다', async () => {
    vi.mocked(login).mockResolvedValue(NEW_USER);
    renderLogin();

    fillCredentials();
    click('로그인');

    await waitFor(() => {
      expect(location()).toBe('/onboarding/taste');
    });
  });

  it('자격 증명이 틀리면 다시 입력하라고 안내하고 화면에 머문다', async () => {
    rejectWith(401, 'INVALID_CREDENTIALS');
    renderLogin();

    fillCredentials();
    click('로그인');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이메일 또는 비밀번호가 올바르지 않습니다.',
    );
    expect(location()).toBe('/login');
  });

  it('시도 제한은 기다리라고 안내하고 재발송을 내보내지 않는다', async () => {
    rejectWith(429, 'RATE_LIMITED');
    renderLogin();

    fillCredentials();
    click('로그인');

    expect(await screen.findByRole('alert')).toHaveTextContent('로그인 시도가 너무 많습니다.');
    expect(screen.queryByRole('button', { name: '인증 메일 다시 보내기' })).toBeNull();
  });

  it('서버에 닿지 못하면 연결을 확인하라고 안내한다', async () => {
    rejectWith(0, NETWORK_ERROR);
    renderLogin();

    fillCredentials();
    click('로그인');

    expect(await screen.findByRole('alert')).toHaveTextContent(NETWORK_FAILURE_DESCRIPTION);
    expect(location()).toBe('/login');
  });

  it('서버가 처리하지 못하면 서버 문구 대신 잠시 후 다시 시도하라고 안내한다', async () => {
    rejectWith(500, 'INTERNAL_ERROR');
    renderLogin();

    fillCredentials();
    click('로그인');

    const alert = await screen.findByRole('alert');

    expect(alert).toHaveTextContent(SERVER_FAILURE_DESCRIPTION);
    expect(alert).not.toHaveTextContent('서버가 준 문구');
  });

  it('화면이 정하지 않은 거절은 서버가 준 사유를 그대로 보여준다', async () => {
    rejectWith(400, 'VALIDATION_FAILED');
    renderLogin();

    fillCredentials();
    click('로그인');

    expect(await screen.findByRole('alert')).toHaveTextContent('서버가 준 문구');
  });

  it('소셜 로그인은 아직 누를 수 없다', () => {
    renderLogin();

    expect(screen.getByRole('button', { name: '카카오 로그인' })).toBeDisabled();
  });

  it('비밀번호 찾기는 0.7로 간다', () => {
    renderLogin();

    follow('비밀번호 찾기');

    expect(location()).toBe('/account/find');
  });

  /* 아이디가 곧 이메일이라 찾을 것이 없다(CR-25, 업무 -455) */
  it('아이디 찾기 링크는 없다', () => {
    renderLogin();

    expect(screen.queryByRole('link', { name: '아이디 찾기' })).toBeNull();
  });

  it('회원가입은 가입 방식 선택으로 간다', () => {
    renderLogin();

    follow('회원가입');

    expect(location()).toBe('/signup');
  });
});

/*
 * 보호 라우트가 보낸 로그인 (업무 S15P21E106-610). 가려던 주소가 `state.from`에 있고,
 * 로그인이 끝나면 그리로 돌아간다. 앱 밖 주소는 열린 리다이렉트라 받지 않는다.
 */
function FullLocationProbe() {
  const current = useLocation();

  return <div data-testid="full-location">{current.pathname + current.search}</div>;
}

function renderLoginFrom(from: unknown) {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[{ pathname: '/login', state: { from } }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
        </Routes>
        <FullLocationProbe />
      </MemoryRouter>
    </AuthProvider>,
  );
}

/*
 * 업무 -771. 한때 로그인한 채로 `/login`에 들어와도 로그인 화면이 그대로 떴다. 로그인 뒤와
 * 같은 규칙으로 앱에 보낸다.
 */
describe('이미 로그인한 사람이 연 로그인 화면', () => {
  it('취향 설정을 마쳤으면 필름으로 보낸다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(ACTIVE_USER);
    renderLogin();

    await waitFor(() => expect(location()).toBe('/film'));
  });

  it('취향 설정을 마치지 않았으면 초기 취향 설정으로 보낸다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(NEW_USER);
    renderLogin();

    await waitFor(() => expect(location()).toBe('/onboarding/taste'));
  });

  it('가려던 주소가 있으면 그리로 보낸다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(ACTIVE_USER);
    renderLoginFrom('/my?tab=unrated');

    await waitFor(() => {
      expect(screen.getByTestId('full-location').textContent).toBe('/my?tab=unrated');
    });
  });

  // 로그인 직후와 같은 우선순위다 — 가려던 화면들이 온보딩 완료를 전제한다(!542 리뷰)
  it('취향 설정을 마치지 않았으면 가려던 주소보다 초기 취향 설정이 먼저다', async () => {
    vi.mocked(restoreSession).mockResolvedValue(NEW_USER);
    renderLoginFrom('/my?tab=unrated');

    await waitFor(() => {
      expect(screen.getByTestId('full-location').textContent).toBe('/onboarding/taste');
    });
  });
});

describe('보호 라우트에서 온 로그인', () => {
  it('로그인이 끝나면 가려던 주소로 돌아간다', async () => {
    vi.mocked(login).mockResolvedValue(ACTIVE_USER);
    renderLoginFrom('/my?tab=unrated');

    fillCredentials();
    click('로그인');

    await waitFor(() => {
      expect(screen.getByTestId('full-location').textContent).toBe('/my?tab=unrated');
    });
  });

  it('온보딩을 마치지 않았으면 가려던 주소보다 초기 취향 설정이 먼저다', async () => {
    vi.mocked(login).mockResolvedValue(NEW_USER);
    renderLoginFrom('/my');

    fillCredentials();
    click('로그인');

    await waitFor(() => {
      expect(screen.getByTestId('full-location').textContent).toBe('/onboarding/taste');
    });
  });

  it('앱 밖 주소는 돌아갈 곳으로 쓰지 않는다', async () => {
    vi.mocked(login).mockResolvedValue(ACTIVE_USER);
    renderLoginFrom('//evil.example/steal');

    fillCredentials();
    click('로그인');

    await waitFor(() => {
      expect(screen.getByTestId('full-location').textContent).toBe('/film');
    });
  });
});
