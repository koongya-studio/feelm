/*
 * 0.6 로그인 — 이동 분기 테스트 (업무 S15P21E106-302)
 *
 * 와이어프레임 이동표는 로그인 뒤 가는 곳을 두 갈래로 나눈다.
 * 0.6 ⇢ 1.1 신규 사용자가 처음 로그인한 경우
 * 0.6 → 2.1 취향 설정을 완료한 기존 사용자 로그인
 *
 * 무엇으로 갈라지는지가 와이어프레임에 적혀 있지 않아 `onboardingCompleted`로 정했다.
 * 화면에서 정한 규칙이라 회귀가 나기 쉬우므로 두 갈래를 모두 고정한다.
 *
 * `아이디 찾기`와 `비밀번호 찾기`가 같은 0.7로 가면서 탭만 다르다는 것도 함께 본다.
 * 경로가 같아 눈으로는 구분되지 않는 부분이다.
 */
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import LoginPage from './index';
import { renderWithRouter } from '../../../test/render';

vi.mock('../../../mocks/user', () => ({
  mockMyProfile: { onboardingCompleted: true },
}));

const { mockMyProfile } = await import('../../../mocks/user');

function click(name: string) {
  fireEvent.click(screen.getByRole('button', { name }));
}

/** 계정 도움말 줄은 이동이므로 링크다 */
function follow(name: string) {
  fireEvent.click(screen.getByRole('link', { name }));
}

/** 이동 결과. 부분 일치로 보면 `/`가 모든 경로에 걸리므로 전체를 비교한다 */
function location() {
  return screen.getByTestId('location').textContent;
}

describe('0.6 로그인', () => {
  it('취향 설정을 마친 사용자는 홈으로 간다', () => {
    mockMyProfile.onboardingCompleted = true;
    renderWithRouter(<LoginPage />, { route: '/login' });

    click('로그인');

    expect(location()).toBe('/');
  });

  it('취향 설정을 마치지 않은 사용자는 초기 취향 설정으로 간다', () => {
    mockMyProfile.onboardingCompleted = false;
    renderWithRouter(<LoginPage />, { route: '/login' });

    click('로그인');

    expect(location()).toBe('/onboarding/taste');
  });

  it('소셜 로그인도 같은 분기를 탄다', () => {
    mockMyProfile.onboardingCompleted = false;
    renderWithRouter(<LoginPage />, { route: '/login' });

    click('카카오 로그인');

    expect(location()).toBe('/onboarding/taste');
  });

  it('비밀번호 찾기는 계정 찾기 화면으로 간다', () => {
    renderWithRouter(<LoginPage />, { route: '/login' });

    follow('비밀번호 찾기');

    expect(location()).toBe('/account/find');
  });

  it('아이디 찾기도 같은 화면으로 간다', () => {
    renderWithRouter(<LoginPage />, { route: '/login' });

    follow('아이디 찾기');

    expect(location()).toBe('/account/find');
  });

  it('회원가입은 가입 방식 선택으로 간다', () => {
    renderWithRouter(<LoginPage />, { route: '/login' });

    follow('회원가입');

    expect(location()).toBe('/signup');
  });
});
