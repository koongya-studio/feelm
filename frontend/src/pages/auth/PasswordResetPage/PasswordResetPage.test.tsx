/*
 * 0.7-A 비밀번호 재설정 — 유효성 테스트 (업무 S15P21E106-302)
 *
 * `8자 이상, 영문과 숫자 포함`은 와이어프레임이 직접 적어 둔 유일한 유효성 규칙이다.
 * 화면에 문구로 보이는 것과 실제로 검사하는 것이 어긋나면 사용자는 이유를 알 수 없으므로
 * 둘을 함께 고정한다.
 */
import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PasswordResetPage from './index';
import { isValidPassword } from '../password';
import { renderWithRouter } from '../../../test/render';

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
    ['abcd1234', true],
    ['abcdefgh', false], // 숫자가 없다
    ['12345678', false], // 영문이 없다
    ['abc1234', false], // 7자
  ])('%s → %s', (value, expected) => {
    expect(isValidPassword(value)).toBe(expected);
  });
});

describe('0.7-A 비밀번호 재설정', () => {
  it('규칙에 맞고 두 값이 같으면 로그인으로 간다', () => {
    renderWithRouter(<PasswordResetPage />, { route: '/account/reset' });

    fill('abcd1234', 'abcd1234');

    expect(location()).toBe('/login');
  });

  it('두 값이 다르면 이동하지 않고 알린다', () => {
    renderWithRouter(<PasswordResetPage />, { route: '/account/reset' });

    fill('abcd1234', 'abcd9999');

    expect(screen.getByRole('alert')).toHaveTextContent('비밀번호가 서로 달라요');
    expect(location()).toBe('/account/reset');
  });

  it('규칙에 맞지 않으면 화면에 적힌 문구 그대로 알린다', () => {
    renderWithRouter(<PasswordResetPage />, { route: '/account/reset' });

    fill('abcdefgh', 'abcdefgh');

    expect(screen.getByRole('alert')).toHaveTextContent('8자 이상, 영문과 숫자 포함');
    expect(location()).toBe('/account/reset');
  });
});
