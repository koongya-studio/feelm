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
vi.mock('../../../api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/users')>()),
  checkNicknameAvailability: vi.fn(),
}));

const { signup } = await import('../../../api/auth');
const { checkNicknameAvailability } = await import('../../../api/users');

beforeEach(() => {
  vi.mocked(signup).mockReset().mockResolvedValue({
    userId: 1024,
    email: 'user@example.com',
    nickname: '팝콘러버',
    status: 'PENDING_VERIFICATION',
  });
  vi.mocked(checkNicknameAvailability)
    .mockReset()
    .mockImplementation(async (nickname) => ({ nickname, available: true, reason: null }));
});

async function checkNickname(value = '팝콘러버') {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: '중복 확인' }));
}

async function fill({ confirm = 'p@ssw0rd1' }: { confirm?: string } = {}) {
  await checkNickname();
  await screen.findByText('사용할 수 있는 닉네임이에요.');
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

    await fill();
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

    await fill({ confirm: 'different1!' });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호가 서로 달라요.');
    expect(signup).not.toHaveBeenCalled();
  });

  it('성별을 고르지 않으면 보내지 않는다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    await checkNickname();
    await screen.findByText('사용할 수 있는 닉네임이에요.');
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

    await fill();
    submit();

    const alerts = await screen.findAllByRole('alert');

    expect(alerts.some((alert) => alert.textContent?.includes(expected))).toBe(true);
  });

  it('가입에 성공하면 완료 화면으로 간다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    await fill();
    submit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/signup/complete');
    });
  });
});

/*
 * 타자기 종이 (업무 S15P21E106-319)
 *
 * 줄은 앞 줄에 입력해야 나온다. 나오지 않은 줄은 `inert`라 초점이 가지 않는다. 한 번
 * 나온 줄이 입력을 지웠다고 다시 들어가면 입력하던 칸이 사라지므로 그것도 고정한다.
 */
describe('0.4 타자기 종이', () => {
  const lineOf = (label: string) => screen.getByLabelText(label).closest('[data-fed]');

  it('처음에는 닉네임 줄만 나와 있다', () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    expect(lineOf('닉네임')).toHaveAttribute('data-fed', 'true');
    expect(lineOf('생년월일')).toHaveAttribute('inert');
    expect(lineOf('비밀번호 재확인')).toHaveAttribute('inert');
  });

  it('앞 줄에 입력하면 다음 줄이 나오고, 지워도 들어가지 않는다', () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '팝' } });

    expect(lineOf('생년월일')).not.toHaveAttribute('inert');
    expect(lineOf('이메일')).toHaveAttribute('inert');

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '' } });

    expect(lineOf('생년월일')).not.toHaveAttribute('inert');
  });
});

/*
 * 닉네임 중복 확인 (업무 S15P21E106-319)
 *
 * 확인을 거치지 않았거나, 확인한 뒤 닉네임을 고친 채로는 가입을 보내지 않는다. 결과가
 * 풀리는 조건이 눈에 안 보이는 상태라 여기서 고정한다.
 */
describe('0.4 닉네임 중복 확인', () => {
  it('확인하지 않으면 가입을 보내지 않는다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '팝콘러버' } });
    fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
    fireEvent.click(screen.getByRole('button', { name: '여성' }));
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'p@ssw0rd1' } });
    fireEvent.change(screen.getByLabelText('비밀번호 재확인'), { target: { value: 'p@ssw0rd1' } });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('닉네임 중복 확인을 해 주세요.');
    expect(signup).not.toHaveBeenCalled();
  });

  it('입력한 닉네임으로 확인하고 사용 가능을 알린다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    await checkNickname();

    expect(await screen.findByText('사용할 수 있는 닉네임이에요.')).toBeInTheDocument();
    expect(checkNicknameAvailability).toHaveBeenCalledWith('팝콘러버');
  });

  it('이미 쓰는 닉네임이면 오류로 알린다', async () => {
    vi.mocked(checkNicknameAvailability).mockResolvedValue({
      nickname: '팝콘러버',
      available: false,
      reason: 'DUPLICATED',
    });
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    await checkNickname();

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 사용 중인 닉네임이에요.');
  });

  it('형식이 틀리면 서버를 부르지 않고 규칙을 알린다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    await checkNickname('팝');

    expect(await screen.findByRole('alert')).toHaveTextContent('2~10자');
    expect(checkNicknameAvailability).not.toHaveBeenCalled();
  });

  it('확인한 뒤 닉네임을 고치면 확인이 풀린다', async () => {
    renderWithRouter(<EmailSignupPage />, { route: '/signup/email' });

    await fill();
    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '팝콘러버2' } });

    expect(screen.queryByText('사용할 수 있는 닉네임이에요.')).toBeNull();

    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('닉네임 중복 확인을 해 주세요.');
    expect(signup).not.toHaveBeenCalled();
  });
});
