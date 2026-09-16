/*
 * 0.7-A 비밀번호 재설정 (업무 S15P21E106-302 · S15P21E106-391)
 *
 * 규칙의 출처는 API 계약이다(S15P21E106-448). 화면에 문구로 보이는 것과 실제로
 * 검사하는 것, 그리고 서버가 검사하는 것이 어긋나면 사용자는 안내대로 입력하고도
 * 거절당하는 이유를 알 수 없으므로 셋을 같은 값으로 고정한다.
 *
 * 391에서 이 화면이 **메일 링크로 열리는 화면**이 되었다(CR-21). 여기서 고정하는 것은
 * 주소의 토큰을 그대로 API-011에 넘기는 것과, 계약이 가르는 실패를 화면도 가르는 것이다.
 * 링크가 끊긴 실패(위조·만료)는 이 화면에서 다시 입력해도 소용이 없어 0.7로 돌려보내야
 * 하고, 정책 위반은 여기서 다시 입력하면 된다. 한 문구로 뭉뚱그리면 그 차이가 사라진다.
 *
 * 규칙 자체는 첫 describe가 따로 고정하므로, 거동 테스트는 규칙을 통과하는 값 하나를
 * 상수로 두고 규칙 변경에 흔들리지 않게 한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PasswordResetPage from './index';
import { isValidPassword } from '../password';
import { ApiError } from '../../../api/apiError';
import { resetPassword } from '../../../api/auth';
import { renderWithRouter } from '../../../test/render';

vi.mock('../../../api/auth', () => ({ resetPassword: vi.fn() }));

/** 계약(API-011)의 규칙을 만족한다 — 8~64자이며 영문·숫자·특수문자를 각각 포함한다 */
const NEW_PASSWORD = 'n3wP@ss!';

beforeEach(() => {
  vi.mocked(resetPassword).mockReset().mockResolvedValue(undefined);
});

function render(token: string | null = 'reset-token') {
  return renderWithRouter(<PasswordResetPage />, {
    route: token === null ? '/account/reset' : `/account/reset?token=${token}`,
  });
}

function fill(password: string, confirmation: string) {
  fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('새 비밀번호 확인'), { target: { value: confirmation } });
  fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
}

/** 이동 결과. 부분 일치로 보면 `/`가 모든 경로에 걸리므로 전체를 비교한다 */
function location() {
  return screen.getByTestId('location').textContent;
}

describe('비밀번호 규칙', () => {
  it.each([
    ['p@ssw0rd1', true],
    ['abcd1234', false], // 특수문자가 없다
    ['abcdefg!', false], // 숫자가 없다
    ['1234567!', false], // 영문이 없다
    ['p@ssw0r', false], // 7자
    [`p@ssw0rd${'1'.repeat(56)}`, true], // 64자
    [`p@ssw0rd${'1'.repeat(57)}`, false], // 65자
    ['p@ss w0rd1', false], // 공백은 쓸 수 없다
  ])('%s → %s', (value, expected) => {
    expect(isValidPassword(value)).toBe(expected);
  });
});

describe('0.7-A 비밀번호 재설정', () => {
  it('주소의 토큰을 그대로 API-011에 넘긴다', async () => {
    render('opaque-token');

    fill(NEW_PASSWORD, NEW_PASSWORD);

    await waitFor(() => expect(resetPassword).toHaveBeenCalledWith('opaque-token', NEW_PASSWORD));
  });

  it('성공하면 다시 로그인해야 한다고 알린다', async () => {
    render();

    fill(NEW_PASSWORD, NEW_PASSWORD);

    expect(await screen.findByRole('status')).toHaveTextContent('새 비밀번호로 다시 로그인');

    fireEvent.click(screen.getByRole('button', { name: '로그인하러 가기' }));

    expect(location()).toBe('/login');
  });

  it('주소에 토큰이 없으면 입력을 주지 않고 메일을 다시 받게 한다', () => {
    render(null);

    expect(screen.getByRole('alert')).toHaveTextContent('재설정 링크가 올바르지 않아요');
    expect(screen.queryByLabelText('새 비밀번호')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '재설정 메일 다시 받기' }));

    expect(location()).toBe('/account/find');
  });

  it('만료된 링크는 여기서 다시 입력하게 두지 않고 0.7로 보낸다', async () => {
    vi.mocked(resetPassword).mockRejectedValue(
      new ApiError(410, 'RESET_TOKEN_EXPIRED', '재설정 토큰이 만료되었습니다.'),
    );

    render();

    fill(NEW_PASSWORD, NEW_PASSWORD);

    expect(await screen.findByRole('alert')).toHaveTextContent('재설정 링크가 만료됐어요');
    expect(screen.queryByLabelText('새 비밀번호')).not.toBeInTheDocument();
  });

  it('정책 위반은 입력을 그대로 두고 서버 문구를 보여준다', async () => {
    vi.mocked(resetPassword).mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다.'),
    );

    render();

    fill(NEW_PASSWORD, NEW_PASSWORD);

    expect(await screen.findByText('요청 값이 올바르지 않습니다.')).toBeInTheDocument();
    expect(screen.getByLabelText('새 비밀번호')).toBeInTheDocument();
  });

  it('두 값이 다르면 보내지 않고 알린다', () => {
    render();

    fill(NEW_PASSWORD, 'other9Pass!');

    expect(screen.getByRole('alert')).toHaveTextContent('비밀번호가 서로 달라요');
    expect(resetPassword).not.toHaveBeenCalled();
    expect(location()).toBe('/account/reset?token=reset-token');
  });

  it('규칙에 맞지 않으면 화면에 적힌 문구 그대로 알리고 보내지 않는다', () => {
    render();

    // 특수문자가 없다. 옛 화면 규칙은 통과시키던 값이다(S15P21E106-448)
    fill('abcd1234', 'abcd1234');

    expect(screen.getByRole('alert')).toHaveTextContent('8~64자, 영문·숫자·특수문자 포함');
    expect(resetPassword).not.toHaveBeenCalled();
  });
});
