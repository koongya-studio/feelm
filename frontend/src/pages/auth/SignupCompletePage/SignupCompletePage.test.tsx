/*
 * 0.5 회원가입 완료 — 두 경로가 같은 자리로 들어온다 (업무 S15P21E106-366 · -637)
 *
 * 이동표에서 0.2(소셜)와 0.4(이메일)가 같은 화면으로 들어오고 **결과도 같다**. 이메일
 * 가입도 인증번호로 소유를 먼저 확인해 계정이 곧바로 `ACTIVE`이고 가입 응답이 세션을 함께
 * 내리므로(S15P21E106-603), 인증 메일 안내와 재발송(CR-19)은 사라졌다. 두 경로 모두
 * `시작하기`로 온보딩까지 이어진다.
 *
 * **가입을 마쳤다는 표시가 없으면 성공 화면을 그리지 않는다**(업무 -610). 주소를 직접
 * 쳐서 들어오거나 표시가 사라진 뒤 새로고침하면 0.2로 보낸다.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { render } from '@testing-library/react';
import SignupCompletePage from './index';
import {
  clearSignupCompletion,
  markSignupCompleted,
  readSignupCompletion,
} from '../../../utils/signupCompletion';

beforeEach(() => {
  clearSignupCompletion();
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
  it('이메일 가입도 곧바로 온보딩으로 간다', () => {
    markSignupCompleted({ kind: 'email' });
    renderComplete();

    expect(screen.getByText('첫 취향 필름을 만들어볼까요?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '시작하기' })).toBeInTheDocument();
    // 인증 메일 안내와 재발송은 인증번호 전환으로 사라졌다(S15P21E106-637)
    expect(screen.queryByText(/인증 메일/)).toBeNull();
    expect(screen.queryByRole('button', { name: '로그인하러 가기' })).toBeNull();
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
    expect(screen.getByText('가입 방법 선택')).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/signup');
  });

  it('새로고침해도 세션 저장소의 표시로 같은 화면을 다시 그린다', () => {
    markSignupCompleted({ kind: 'email' });

    const first = renderComplete();
    expect(screen.getByText('첫 취향 필름을 만들어볼까요?')).toBeInTheDocument();
    first.unmount();

    // 떠나지 않고 새로고침한 것과 같다 — 표시가 남아 있으므로 성공 화면이다
    renderComplete();
    expect(screen.getByText('첫 취향 필름을 만들어볼까요?')).toBeInTheDocument();
  });
});
