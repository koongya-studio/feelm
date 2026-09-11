/*
 * 0.4 이메일 회원가입 — 계약 필드와 실패 안내 (업무 S15P21E106-366)
 *
 * 이 화면은 와이어프레임과 계약이 달라 사양을 고친 자리다(CR-19 · CR-20). 인증번호 입력이
 * 사라지고 나이가 생년월일로 바뀌었는데, 되돌아가기 쉬운 변경이라 무엇을 보내는지 고정한다.
 *
 * 실패도 갈라 본다. 이메일 중복과 닉네임 중복은 사용자가 고칠 수 있는 것이 서로 다르고,
 * 요청 제한은 고칠 것이 없다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import EmailSignupPage from './index';
import { ApiError } from '../../../api/apiError';
import { renderWithRouter } from '../../../test/render';

vi.mock('../../../api/auth', () => ({ signup: vi.fn() }));

const { signup } = await import('../../../api/auth');

beforeEach(() => {
  vi.mocked(signup).mockReset().mockResolvedValue({
    userId: 1024,
    email: 'user@example.com',
    nickname: '팝콘러버',
    status: 'PENDING_VERIFICATION',
  });
});

function fill({ confirm = 'p@ssw0rd1' }: { confirm?: string } = {}) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '팝콘러버' } });
  fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
  fireEvent.click(screen.getByRole('button', { name: '여성' }));
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'p@ssw0rd1' } });
  fireEvent.change(screen.getByLabelText('비밀번호 재확인'), { target: { value: confirm } });
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '가입하기' }));
}

describe('0.4 이메일 회원가입', () => {
  it('계약이 받는 필드를 그대로 보낸다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    fill();
    submit();

    await waitFor(() => {
      expect(signup).toHaveBeenCalledWith({
        email: 'user@example.com',
        password: 'p@ssw0rd1',
        nickname: '팝콘러버',
        birthDate: '1999-03-14',
        gender: 'FEMALE',
      });
    });
  });

  it('인증번호 입력은 없다', () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    expect(screen.queryByLabelText('인증번호')).toBeNull();
    expect(screen.queryByRole('button', { name: '인증요청' })).toBeNull();
  });

  it('비밀번호가 서로 다르면 보내지 않는다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    fill({ confirm: 'different1!' });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호가 서로 달라요.');
    expect(signup).not.toHaveBeenCalled();
  });

  it('성별을 고르지 않으면 보내지 않는다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '팝콘러버' } });
    fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'p@ssw0rd1' } });
    fireEvent.change(screen.getByLabelText('비밀번호 재확인'), {
      target: { value: 'p@ssw0rd1' },
    });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('성별을 선택해 주세요.');
    expect(signup).not.toHaveBeenCalled();
  });

  it.each([
    ['EMAIL_DUPLICATED', '이미 가입된 이메일이에요'],
    ['NICKNAME_DUPLICATED', '이미 사용 중인 닉네임이에요'],
    ['RATE_LIMITED', '요청이 너무 많아요'],
  ])('%s는 구분해 안내한다', async (code, expected) => {
    vi.mocked(signup).mockRejectedValue(new ApiError(409, code, '서버가 준 문구'));
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    fill();
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent(expected);
  });

  it('가입에 성공하면 완료 화면으로 간다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    fill();
    submit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/signup/complete');
    });
  });
});
