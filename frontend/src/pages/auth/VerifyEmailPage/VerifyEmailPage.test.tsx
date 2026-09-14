/*
 * 0.8 이메일 인증 — 계약이 가르는 네 상태 (업무 S15P21E106-366)
 *
 * API-002는 성공 외에 세 가지 실패를 구분한다. 토큰 위조(400), 만료(410), 이미 인증됨(409)이다.
 * 셋을 같은 문구로 뭉뚱그리면 사용자가 다음에 할 일을 알 수 없고, 특히 만료만 재발송이
 * 필요하다. 화면에서 눈으로 구분하기 어려운 부분이라 여기서 고정한다.
 *
 * 토큰이 1회용이라는 점도 함께 본다. 같은 토큰을 두 번 보내면 두 번째는 실패하므로
 * StrictMode가 효과를 두 번 실행해도 호출은 한 번이어야 한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import VerifyEmailPage from './index';
import { ApiError } from '../../../api/apiError';
import { renderWithRouter } from '../../../test/render';

vi.mock('../../../api/auth', () => ({
  verifyEmail: vi.fn(),
  resendVerificationMail: vi.fn(),
}));

const { resendVerificationMail, verifyEmail } = await import('../../../api/auth');

const TOKEN = 'kV9tQ2xZbG5fUjRhN3RXcw';

beforeEach(() => {
  vi.mocked(verifyEmail).mockReset();
  vi.mocked(resendVerificationMail).mockReset().mockResolvedValue(undefined);
});

function renderVerify(token: string | null = TOKEN) {
  return renderWithRouter(<VerifyEmailPage />, {
    route: token === null ? '/verify-email' : `/verify-email?token=${token}`,
  });
}

function rejectWith(status: number, code: string) {
  vi.mocked(verifyEmail).mockRejectedValue(new ApiError(status, code, '서버가 준 문구'));
}

describe('0.8 이메일 인증', () => {
  it('주소의 토큰으로 인증하고 0.8-A 완료 화면으로 넘긴다', async () => {
    vi.mocked(verifyEmail).mockResolvedValue({ userId: 1024, status: 'ACTIVE' });
    renderVerify();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/verify-email/complete');
    });
    expect(verifyEmail).toHaveBeenCalledWith(TOKEN);
  });

  it('토큰이 1회용이므로 한 번만 호출한다', async () => {
    vi.mocked(verifyEmail).mockResolvedValue({ userId: 1024, status: 'ACTIVE' });
    renderVerify();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/verify-email/complete');
    });

    expect(verifyEmail).toHaveBeenCalledTimes(1);
  });

  it('토큰이 위조되면 링크가 올바르지 않다고 알리고 재발송을 내보내지 않는다', async () => {
    rejectWith(400, 'VERIFICATION_TOKEN_INVALID');
    renderVerify();

    expect(await screen.findByText(/링크가 올바르지 않아요/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '인증 메일 다시 받기' })).toBeNull();
  });

  it('주소에 토큰이 없으면 호출하지 않고 같은 안내를 보여준다', async () => {
    renderVerify(null);

    expect(await screen.findByText(/링크가 올바르지 않아요/)).toBeInTheDocument();
    expect(verifyEmail).not.toHaveBeenCalled();
  });

  it('만료되면 이메일을 받아 재발송한다', async () => {
    rejectWith(410, 'VERIFICATION_TOKEN_EXPIRED');
    renderVerify();

    expect(await screen.findByText(/링크가 만료됐어요/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('가입한 이메일'), {
      target: { value: 'user@example.com' },
    });
    fireEvent.click(screen.getByRole('button', { name: '인증 메일 다시 받기' }));

    await waitFor(() => {
      expect(resendVerificationMail).toHaveBeenCalledWith('user@example.com');
    });
    expect(await screen.findByText(/다시 보냈습니다/)).toBeInTheDocument();
  });

  it('이미 인증된 계정은 재발송 없이 로그인으로 안내한다', async () => {
    rejectWith(409, 'ALREADY_VERIFIED');
    renderVerify();

    expect(await screen.findByText(/이미 인증을 마친 계정이에요/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '인증 메일 다시 받기' })).toBeNull();
    expect(screen.getByRole('button', { name: '로그인하러 가기' })).toBeInTheDocument();
  });

  it('계약에 없는 실패는 서버 문구를 그대로 보여준다', async () => {
    rejectWith(500, 'INTERNAL_ERROR');
    renderVerify();

    expect(await screen.findByText('서버가 준 문구')).toBeInTheDocument();
  });
});
