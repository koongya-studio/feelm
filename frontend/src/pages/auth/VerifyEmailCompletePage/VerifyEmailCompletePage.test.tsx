/*
 * 0.8-A 이메일 인증 완료 (업무 S15P21E106-319)
 *
 * 완료를 알리고 로그인으로 보내는 것만 한다. 이동이 끊기면 사용자가 인증을 마치고도
 * 갈 곳이 없으므로 화살표를 고정한다.
 */
import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import VerifyEmailCompletePage from './index';
import { renderWithRouter } from '../../../test/render';

describe('0.8-A 이메일 인증 완료', () => {
  it('완료를 알리고 로그인으로 보낸다', () => {
    renderWithRouter(<VerifyEmailCompletePage />, { route: '/verify-email/complete' });

    expect(screen.getByRole('heading')).toHaveTextContent('이메일 인증이완료되었습니다');

    fireEvent.click(screen.getByRole('button', { name: '로그인하러 가기' }));

    expect(screen.getByTestId('location').textContent).toBe('/login');
  });
});
