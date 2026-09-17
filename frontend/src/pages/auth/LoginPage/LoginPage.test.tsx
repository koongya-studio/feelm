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
import { fireEvent, screen, waitFor } from '@testing-library/react';
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
  resendVerificationMail: vi.fn(),
}));

const { login, resendVerificationMail, restoreSession } = await import('../../../api/auth');

const ACTIVE_USER = { userId: 1, nickname: '팝콘러버', onboardingCompleted: true };
const NEW_USER = { ...ACTIVE_USER, onboardingCompleted: false };

beforeEach(() => {
  vi.mocked(login).mockReset();
  vi.mocked(resendVerificationMail).mockReset().mockResolvedValue(undefined);
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

  it('미인증 계정은 인증 메일을 다시 보낼 수 있다', async () => {
    rejectWith(403, 'EMAIL_NOT_VERIFIED');
    renderLogin();

    fillCredentials();
    click('로그인');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이메일 인증을 마쳐야 로그인할 수 있습니다.',
    );

    click('인증 메일 다시 보내기');

    await waitFor(() => {
      expect(resendVerificationMail).toHaveBeenCalledWith('user@example.com');
    });
    expect(await screen.findByRole('status')).toHaveTextContent('인증 메일을 다시 보냈습니다.');
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
