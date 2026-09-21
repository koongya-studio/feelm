/*
 * 데스크톱 좌측 내비 (업무 -782)
 *
 * 다섯 항목이 시안 순서로 놓이고, 지금 어디에 있는지를 한 항목만 표시한다. 하단 내비의
 * 3탭 계약(`TAB_MATCH`)과 달리 검색과 추천 영화가 제 자리를 갖는 것이 이 내비의 핵심이라
 * 그 둘이 필름에 묻히지 않는지를 함께 고정한다.
 */
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import DesktopNav from './index';
import { renderWithRouter } from '../../test/render';
import type { ShellAccount } from '../../hooks/useShellAccount';

const account: ShellAccount = {
  userId: 7,
  nickname: '김난다',
  email: 'nanda@feelm.app',
  avatarUrl: null,
};

function nav(route: string, value: ShellAccount | null = account) {
  return renderWithRouter(<DesktopNav account={value} />, { route });
}

describe('DesktopNav', () => {
  it('시안 순서로 다섯 곳에 간다', () => {
    nav('/film');

    const items = screen.getByRole('navigation', { name: '주요 화면' }).querySelectorAll('a');

    expect([...items].map((item) => item.textContent)).toEqual([
      '취향 필름',
      '추천 영화',
      '파티팝콘',
      '검색',
      'MY',
    ]);
    expect([...items].map((item) => item.getAttribute('href'))).toEqual([
      '/film',
      '/recommendations',
      '/party',
      '/search',
      '/my',
    ]);
  });

  it('지금 있는 화면 하나만 선택으로 표시한다', () => {
    nav('/party');

    expect(screen.getByRole('link', { name: '파티팝콘' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: '취향 필름' })).not.toHaveAttribute('aria-current');
  });

  it('검색은 필름이 아니라 검색으로 표시한다', () => {
    nav('/search');

    expect(screen.getByRole('link', { name: '검색' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: '취향 필름' })).not.toHaveAttribute('aria-current');
  });

  it('추천 영화도 필름과 따로 표시한다', () => {
    nav('/recommendations');

    expect(screen.getByRole('link', { name: '추천 영화' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: '취향 필름' })).not.toHaveAttribute('aria-current');
  });

  it('하위 경로에서도 상위 항목에 머문다', () => {
    nav('/film/popcorn');

    expect(screen.getByRole('link', { name: '취향 필름' })).toHaveAttribute('aria-current', 'page');
  });

  it('계정 행이 이름과 이메일을 보여주고 MY로 간다', () => {
    nav('/film');

    const link = screen.getByRole('link', { name: /김난다/ });

    expect(link).toHaveAttribute('href', '/my');
    expect(screen.getByText('nanda@feelm.app')).toBeInTheDocument();
  });

  it('프로필을 받지 못했으면 이름만 둔다', () => {
    nav('/film', { ...account, email: null });

    expect(screen.getByText('김난다')).toBeInTheDocument();
    expect(screen.queryByText('nanda@feelm.app')).not.toBeInTheDocument();
  });

  it('로그인 정보가 아직 없으면 계정 행을 그리지 않는다', () => {
    nav('/film', null);

    expect(screen.queryByText('김난다')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '설정' })).toHaveAttribute('href', '/my/settings');
  });
});
