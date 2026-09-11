/*
 * 7.2-B 다른 사용자 공개 프로필 → 7.2-C 취향 비교 (업무 S15P21E106-305)
 *
 * 이동표 `7.2-B → 7.2-C`는 **대상 사용자가 이어져야** 성립한다. 비교 화면으로 가기만
 * 하고 누구와 비교하는지가 빠지면 화면은 열리지만 경로는 끊긴 것이다
 * (screen-spec-my.md 2장 9번).
 */
import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PublicProfilePage from './index';
import { renderWithRouter } from '../../../test/render';

/** `mocks/publicProfiles.ts`의 무비버디. 5.8 파티원과 같은 사람이다 */
const USER_ID = 2048;

async function renderProfile(userId: number = USER_ID) {
  const result = renderWithRouter(<PublicProfilePage />, {
    route: `/users/${userId}`,
    path: '/users/:userId',
  });
  // 화면 제목은 히어로 안의 이름이다. 헤더를 없애고 이미지가 상단을 차지한다(ProfileHero)
  await screen.findByRole('heading', { name: '무비버디' });

  return result;
}

describe('7.2-B 다른 사용자 공개 프로필', () => {
  it('비교하기를 누르면 그 사용자의 비교 화면으로 간다', async () => {
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '나와 취향 비교하기' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(`/users/${USER_ID}/compare`),
    );
  });

  it('팔로워 대신 프레임 · 팝콘 · 나와의 일치도를 보여준다', async () => {
    // Feelm에 팔로워 개념이 없다. 레퍼런스의 3열을 서비스에 맞게 바꾼 자리다
    await renderProfile();

    expect(screen.getByText('프레임')).toBeInTheDocument();
    expect(screen.getByText('나와 일치')).toBeInTheDocument();
    expect(screen.getByText('76%')).toBeInTheDocument();
    expect(screen.queryByText(/팔로워|팔로잉/)).not.toBeInTheDocument();
  });

  it('차단은 확인 안내까지만 하고 실제로 처리하지 않는다', async () => {
    /*
     * 차단 · 신고는 계약이 없다. 이동표 `7.2-B ⇢ 7.8-D`의 7.8-D도 아직 없어
     * 이동하지 않는다(screen-spec-my.md 2장 14번).
     */
    await renderProfile();

    fireEvent.click(screen.getByRole('button', { name: '더 보기' }));
    fireEvent.click(screen.getByRole('button', { name: '사용자 차단' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      '무비버디 님을 차단하면 서로의 콘텐츠가 보이지 않아요',
    );
    expect(screen.getByTestId('location').textContent).toBe(`/users/${USER_ID}`);
  });

  it('없는 사용자면 안내를 보여준다', async () => {
    renderWithRouter(<PublicProfilePage />, {
      route: '/users/9999',
      path: '/users/:userId',
    });

    expect(await screen.findByText('프로필을 불러오지 못했어요')).toBeInTheDocument();
  });
});
