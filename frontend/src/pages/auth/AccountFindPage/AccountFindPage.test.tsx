/*
 * 0.7 계정 찾기 — 비밀번호 찾기 탭 (업무 S15P21E106-391)
 *
 * 이 탭이 하는 일은 재설정 메일을 요청하는 것 하나다(API-010). 고정할 것은 두 가지다.
 *
 * 하나는 **안내 문구가 가입 여부를 흘리지 않는 것**이다. 계약이 계정 유무와 무관하게
 * `202`로 답하는 이유가 그것인데(API-010 처리 규칙), 화면이 `메일을 보냈어요`라고
 * 단정해 버리면 응답이 숨긴 것을 화면이 드러낸다. 서버 쪽 규칙이라 자동 테스트가
 * 아니면 되돌아가기 쉬운 자리다.
 *
 * 다른 하나는 **아이디 찾기 탭을 건드리지 않은 것**이다. 대응 API가 계약에 없어 그대로
 * 두었으므로, 이 탭을 바꾸다 저쪽 요소가 사라지지 않았는지 함께 본다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import AccountFindPage from './index';
import { ApiError } from '../../../api/apiError';
import { requestPasswordReset } from '../../../api/auth';
import { renderWithRouter } from '../../../test/render';

vi.mock('../../../api/auth', () => ({ requestPasswordReset: vi.fn() }));

beforeEach(() => {
  vi.mocked(requestPasswordReset).mockReset().mockResolvedValue(undefined);
});

function openPasswordTab() {
  renderWithRouter(<AccountFindPage />, { route: '/account/find' });
  fireEvent.click(screen.getByRole('tab', { name: '비밀번호 찾기' }));
}

describe('0.7 비밀번호 찾기', () => {
  it('입력한 이메일로 재설정 메일을 요청한다', async () => {
    openPasswordTab();

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 받기' }));

    await waitFor(() => expect(requestPasswordReset).toHaveBeenCalledWith('user@example.com'));
  });

  it('보냈다고 단정하지 않아 가입 여부가 드러나지 않는다', async () => {
    openPasswordTab();

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      '가입된 계정이 있다면 재설정 링크를 보냈어요',
    );
  });

  it('인증번호를 받지 않는다', () => {
    openPasswordTab();

    expect(screen.queryByLabelText('인증번호')).not.toBeInTheDocument();
  });

  it('요청 제한은 기다리라고 알린다', async () => {
    vi.mocked(requestPasswordReset).mockRejectedValue(
      new ApiError(429, 'RATE_LIMITED', '요청이 너무 많습니다.'),
    );

    openPasswordTab();

    fireEvent.change(screen.getByLabelText('이메일'), { target: { value: 'user@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: '재설정 링크 받기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('요청이 너무 잦아요');
  });
});

describe('0.7 아이디 찾기', () => {
  it('대응 API가 없어 와이어프레임 그대로 두었다', () => {
    renderWithRouter(<AccountFindPage />, { route: '/account/find' });

    expect(screen.getByLabelText('인증번호')).toBeInTheDocument();
    expect(requestPasswordReset).not.toHaveBeenCalled();
  });
});
