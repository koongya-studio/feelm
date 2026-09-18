/*
 * 0.5 회원가입 완료 — 두 경로의 계정 상태가 다르다 (업무 S15P21E106-366)
 *
 * 이동표에서 0.2(소셜)와 0.4(이메일)가 같은 화면으로 들어오는데 계정 상태가 다르다.
 * 소셜은 곧바로 쓸 수 있고 이메일은 인증을 마쳐야 로그인할 수 있다(API-001). 이메일로 온
 * 사용자를 그대로 온보딩으로 보내면 세션이 없어 회원 API가 막히므로, 갈라지는 지점을
 * 고정한다. 화면만 보면 같은 화면이라 눈으로 구분되지 않는다.
 *
 * **가입을 마쳤다는 표시가 없으면 성공 화면을 그리지 않는다**(업무 -610). 주소를 직접
 * 쳐서 들어오거나 표시가 사라진 뒤 새로고침하면 0.2로 보낸다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { render } from '@testing-library/react';
import SignupCompletePage from './index';
import {
  clearSignupCompletion,
  markSignupCompleted,
  readSignupCompletion,
} from '../../../utils/signupCompletion';

vi.mock('../../../api/auth', () => ({ resendVerificationMail: vi.fn() }));

const { resendVerificationMail } = await import('../../../api/auth');

beforeEach(() => {
  clearSignupCompletion();
  vi.mocked(resendVerificationMail).mockReset().mockResolvedValue(undefined);
});

function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname}</div>;
}

function renderComplete() {
  return render(
    <MemoryRouter initialEntries={['/signup/complete']}>
      <Routes>
        <Route path="/signup/complete" element={<SignupCompletePage />} />
        <Route path="/signup" element={<p>가입 방법 선택</p>} />
      </Routes>
      <LocationProbe />
    </MemoryRouter>,
  );
}

describe('0.5 회원가입 완료', () => {
  it('이메일 가입은 인증 메일 안내를 보여준다', () => {
    markSignupCompleted({ kind: 'email', email: 'user@example.com' });
    renderComplete();

    expect(screen.getByText(/인증 메일을 보냈어요/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '로그인하러 가기' })).toBeInTheDocument();
    // 인증 전에는 온보딩으로 보내지 않는다. 세션이 없어 회원 API가 막힌다
    expect(screen.queryByRole('button', { name: '시작하기' })).toBeNull();
  });

  it('이메일 가입은 인증 메일을 다시 보낼 수 있다', async () => {
    markSignupCompleted({ kind: 'email', email: 'user@example.com' });
    renderComplete();

    fireEvent.click(screen.getByRole('button', { name: '메일이 오지 않았나요? 다시 보내기' }));

    await waitFor(() => {
      expect(resendVerificationMail).toHaveBeenCalledWith('user@example.com');
    });
    expect(await screen.findByText(/다시 보냈습니다/)).toBeInTheDocument();
  });

  it('소셜 가입은 곧바로 온보딩으로 가고, 떠나면 표시를 지운다', () => {
    markSignupCompleted({ kind: 'social' });
    renderComplete();

    expect(screen.getByText('첫 취향 필름을 만들어볼까요?')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '시작하기' }));

    expect(screen.getByTestId('location').textContent).toBe('/onboarding/taste');
    expect(readSignupCompletion()).toBeNull();
  });

  it('가입을 마치지 않고 주소로 들어오면 성공 화면 대신 가입 방법 선택으로 보낸다', () => {
    renderComplete();

    expect(screen.queryByText('첫 취향 필름을 만들어볼까요?')).toBeNull();
    expect(screen.queryByText(/인증 메일을 보냈어요/)).toBeNull();
    expect(screen.getByText('가입 방법 선택')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/signup');
  });

  it('새로고침해도 세션 저장소의 표시로 같은 화면을 다시 그린다', () => {
    markSignupCompleted({ kind: 'email', email: 'user@example.com' });

    const first = renderComplete();
    expect(screen.getByText(/인증 메일을 보냈어요/)).toBeInTheDocument();
    first.unmount();

    // 떠나지 않고 새로고침한 것과 같다 — 표시가 남아 있으므로 성공 화면이다
    renderComplete();
    expect(screen.getByText(/인증 메일을 보냈어요/)).toBeInTheDocument();
  });
});
