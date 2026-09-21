/*
 * 0.4 이메일 회원가입 — 인증번호 단계와 계약 필드 (업무 S15P21E106-366 · -637)
 *
 * 이 화면은 가입 계약이 바뀔 때마다 모양이 달라진 자리다. CR-19로 지웠던 인증번호 요소가
 * 인증번호 전환(S15P21E106-601)으로 되살아났고, 가입은 이메일 대신
 * `emailVerificationToken`을 보낸다. 되돌아가기 쉬운 변경이라 무엇을 보내는지 고정한다.
 *
 * 실패도 갈라 본다. 발송 단계의 이메일 중복, 확인 단계의 만료·불일치·소진, 가입 단계의
 * 닉네임 중복은 사용자가 다음에 할 일이 서로 다르다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import EmailSignupPage from './index';
import { ApiError } from '../../../api/apiError';
import { AuthProvider } from '../../../contexts/AuthProvider';
import { renderWithRouter } from '../../../test/render';

/*
 * 가입은 인증 Context를 거친다(`completeEmailSignup`). 가입 응답의 세션을 Context까지
 * 올려야 다음 화면이 로그인 상태로 열리기 때문이다. Provider가 뜰 때 세션 복구를 한 번
 * 부르므로 그 함수도 함께 대역으로 둔다.
 */
vi.mock('../../../api/auth', () => ({
  signup: vi.fn(),
  sendEmailVerificationCode: vi.fn(),
  verifyEmailVerificationCode: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  restoreSession: vi.fn(),
  socialLogin: vi.fn(),
  socialSignup: vi.fn(),
}));
vi.mock('../../../api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/users')>()),
  checkNicknameAvailability: vi.fn(),
}));

const { signup, sendEmailVerificationCode, verifyEmailVerificationCode, restoreSession } =
  await import('../../../api/auth');
const { checkNicknameAvailability } = await import('../../../api/users');

/** 0.4는 인증 Context 안에서만 뜬다 */
function renderSignup() {
  return renderWithRouter(
    <AuthProvider>
      <EmailSignupPage />
    </AuthProvider>,
    { route: '/signup/email' },
  );
}

const TOKEN = 'email-verification-token';

beforeEach(() => {
  vi.mocked(signup)
    .mockReset()
    .mockResolvedValue({ userId: 1024, nickname: '팝콘러버', onboardingCompleted: false });
  vi.mocked(sendEmailVerificationCode).mockReset().mockResolvedValue(undefined);
  // 로그인하지 않은 상태로 화면을 연다
  vi.mocked(restoreSession).mockReset().mockResolvedValue(null);
  vi.mocked(verifyEmailVerificationCode)
    .mockReset()
    .mockResolvedValue({ emailVerificationToken: TOKEN, expiresIn: 600 });
  vi.mocked(checkNicknameAvailability)
    .mockReset()
    .mockImplementation(async (nickname) => ({ nickname, available: true, reason: null }));
});

async function checkNickname(value = '팝콘러버') {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: '중복 확인' }));
}

/** 이메일 입력 → 인증요청 → 인증번호 입력 → 확인까지 */
async function verifyEmail(email = 'user@example.com', code = '032815') {
  fireEvent.change(screen.getByLabelText('이메일'), { target: { value: email } });
  fireEvent.click(screen.getByRole('button', { name: '인증요청' }));
  await screen.findByText(/인증번호를 보냈어요/);
  fireEvent.change(screen.getByLabelText('인증번호'), { target: { value: code } });
  fireEvent.click(screen.getByRole('button', { name: '확인' }));
}

async function fill({
  password = 'p@ssw0rd1',
  confirm = 'p@ssw0rd1',
  verified = true,
}: { password?: string; confirm?: string; verified?: boolean } = {}) {
  await checkNickname();
  await screen.findByText('사용할 수 있는 닉네임이에요.');
  fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
  fireEvent.click(screen.getByRole('button', { name: '여성' }));

  if (verified) {
    await verifyEmail();
    await screen.findByText('이메일 인증을 마쳤어요.');
  } else {
    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
  }

  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('비밀번호 재확인'), { target: { value: confirm } });
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: '가입하기' }));
}

describe('0.4 이메일 회원가입', () => {
  it('계약이 받는 필드를 그대로 보낸다 — 이메일은 토큰 안에 있어 보내지 않는다', async () => {
    renderSignup();

    await fill();
    submit();

    await waitFor(() => {
      expect(signup).toHaveBeenCalledWith({
        emailVerificationToken: TOKEN,
        password: 'p@ssw0rd1',
        nickname: '팝콘러버',
        birthDate: '1999-03-14',
        gender: 'FEMALE',
      });
    });
  });

  it('인증요청과 확인이 각각 API-078 · API-079을 부른다', async () => {
    renderSignup();

    await verifyEmail();

    await waitFor(() => {
      expect(sendEmailVerificationCode).toHaveBeenCalledWith('user@example.com');
      expect(verifyEmailVerificationCode).toHaveBeenCalledWith('user@example.com', '032815');
    });
    expect(await screen.findByText('이메일 인증을 마쳤어요.')).toBeInTheDocument();
  });

  it('인증을 마치지 않으면 가입을 보내지 않는다', async () => {
    renderSignup();

    await fill({ verified: false });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('이메일 인증을 먼저 완료해 주세요.');
    expect(signup).not.toHaveBeenCalled();
  });

  it('이미 가입된 이메일은 발송 단계에서 안내하고 다음으로 넘어가지 않는다', async () => {
    vi.mocked(sendEmailVerificationCode).mockRejectedValue(
      new ApiError(409, 'EMAIL_DUPLICATED', '서버가 준 문구'),
    );
    renderSignup();

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증요청' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 가입된 이메일이에요');
    expect(screen.queryByText(/인증번호를 보냈어요/)).toBeNull();
  });

  it.each([
    ['VERIFICATION_CODE_MISMATCH', 400, '인증번호가 달라요'],
    ['VERIFICATION_CODE_EXPIRED', 410, '인증번호가 만료됐어요'],
    ['VERIFICATION_CODE_EXHAUSTED', 400, '확인 횟수를 넘겼어요'],
  ])('인증번호 실패 %s는 구분해 안내한다', async (code, status, expected) => {
    vi.mocked(verifyEmailVerificationCode).mockRejectedValue(
      new ApiError(status, code, '서버가 준 문구'),
    );
    renderSignup();

    await verifyEmail();

    const alerts = await screen.findAllByRole('alert');

    expect(alerts.some((alert) => alert.textContent?.includes(expected))).toBe(true);
  });

  it('인증번호를 다시 받을 수 있다', async () => {
    renderSignup();

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '인증요청' }));
    await screen.findByText(/인증번호를 보냈어요/);
    fireEvent.click(screen.getByRole('button', { name: '인증요청' }));

    await waitFor(() => {
      expect(sendEmailVerificationCode).toHaveBeenCalledTimes(2);
    });
  });

  /* 토큰은 확인을 마친 주소로 발급됐다. 주소만 고치면 보내는 값과 실제 가입 주소가 어긋난다 */
  it('인증을 마친 뒤 이메일을 고치면 인증이 풀린다', async () => {
    renderSignup();

    await verifyEmail();
    await screen.findByText('이메일 인증을 마쳤어요.');

    expect(screen.getByRole('button', { name: '인증 완료' })).toBeDisabled();

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'other@example.com' } });

    expect(screen.getByRole('button', { name: '인증요청' })).toBeEnabled();
  });

  it('비밀번호가 서로 다르면 보내지 않는다', async () => {
    renderSignup();

    await fill({ confirm: 'different1!' });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('비밀번호가 서로 달라요.');
    expect(signup).not.toHaveBeenCalled();
  });

  it('비밀번호가 계약 규칙에 맞지 않으면 보내지 않는다', async () => {
    renderSignup();

    await fill({ password: 'abcd1234', confirm: 'abcd1234' });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('8~64자, 영문·숫자·특수문자 포함');
    expect(signup).not.toHaveBeenCalled();
  });

  it('성별을 고르지 않으면 보내지 않는다', async () => {
    renderSignup();

    await checkNickname();
    await screen.findByText('사용할 수 있는 닉네임이에요.');
    fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
    await verifyEmail();
    await screen.findByText('이메일 인증을 마쳤어요.');
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
    ['EMAIL_VERIFICATION_TOKEN_INVALID', '이메일 인증이 만료됐어요'],
  ])('가입 실패 %s는 구분해 안내한다', async (code, expected) => {
    vi.mocked(signup).mockRejectedValue(new ApiError(409, code, '서버가 준 문구'));
    renderSignup();

    await fill();
    submit();

    const alerts = await screen.findAllByRole('alert');

    expect(alerts.some((alert) => alert.textContent?.includes(expected))).toBe(true);
  });

  /* 토큰이 만료·소진된 상태로 남으면 사용자가 같은 화면에서 할 수 있는 일이 없다 */
  it('가입에서 토큰이 거절되면 이메일 인증을 다시 할 수 있게 푼다', async () => {
    vi.mocked(signup).mockRejectedValue(
      new ApiError(401, 'EMAIL_VERIFICATION_TOKEN_INVALID', '서버가 준 문구'),
    );
    renderSignup();

    await fill();
    submit();

    await screen.findByText('이메일 인증이 만료됐어요. 인증번호를 다시 받아 주세요.');
    expect(screen.getByRole('button', { name: '인증요청' })).toBeEnabled();
  });

  it('가입에 성공하면 완료 화면으로 간다', async () => {
    renderSignup();

    await fill();
    submit();

    await waitFor(() => {
      expect(screen.getByTestId('location').textContent).toBe('/signup/complete');
    });
  });

  it('소셜 계정으로 가입은 0.2 가입 방식 선택으로 돌아간다', () => {
    // 소셜 버튼만 다시 늘어놓던 0.3을 지웠다(CR-29, 업무 -765)
    renderSignup();

    fireEvent.click(screen.getByRole('button', { name: '소셜 계정으로 가입' }));

    expect(screen.getByTestId('location').textContent).toBe('/signup');
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
    renderSignup();

    expect(lineOf('닉네임')).toHaveAttribute('data-fed', 'true');
    expect(lineOf('생년월일')).toHaveAttribute('inert');
    expect(lineOf('비밀번호 재확인')).toHaveAttribute('inert');
  });

  it('앞 줄에 입력하면 다음 줄이 나오고, 지워도 들어가지 않는다', () => {
    renderSignup();

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
    renderSignup();

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '팝콘러버' } });
    fireEvent.change(screen.getByLabelText('생년월일'), { target: { value: '1999-03-14' } });
    fireEvent.click(screen.getByRole('button', { name: '여성' }));
    await verifyEmail();
    await screen.findByText('이메일 인증을 마쳤어요.');
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'p@ssw0rd1' } });
    fireEvent.change(screen.getByLabelText('비밀번호 재확인'), { target: { value: 'p@ssw0rd1' } });
    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('닉네임 중복 확인을 해 주세요.');
    expect(signup).not.toHaveBeenCalled();
  });

  it('입력한 닉네임으로 확인하고 사용 가능을 알린다', async () => {
    renderSignup();

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
    renderSignup();

    await checkNickname();

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 사용 중인 닉네임이에요.');
  });

  it('형식이 틀리면 서버를 부르지 않고 규칙을 알린다', async () => {
    renderSignup();

    await checkNickname('팝');

    expect(await screen.findByRole('alert')).toHaveTextContent('2~10자');
    expect(checkNicknameAvailability).not.toHaveBeenCalled();
  });

  it('확인한 뒤 닉네임을 고치면 확인이 풀린다', async () => {
    renderSignup();

    await fill();
    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '팝콘러버2' } });

    expect(screen.queryByText('사용할 수 있는 닉네임이에요.')).toBeNull();

    submit();

    expect(await screen.findByRole('alert')).toHaveTextContent('닉네임 중복 확인을 해 주세요.');
    expect(signup).not.toHaveBeenCalled();
  });
});
