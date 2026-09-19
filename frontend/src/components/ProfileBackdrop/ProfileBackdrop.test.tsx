/*
 * 프로필 사진 배경 틀 (업무 S15P21E106-697)
 *
 * 고정하는 것은 셋이다.
 *
 * - **사진이 있으면 그 사진이 화면 배경이다.** 2.4 영화 상세가 포스터를 까는 자리다
 * - **사진이 없으면 어두운 단색으로 떨어지고, 가운데 동그라미 사진이 남는다.** 사진이 배경에
 *   깔려 있을 때만 동그라미를 뺀다 — 같은 얼굴이 두 번 나오지 않게 하려는 것이지, 사진 없는
 *   사람의 자리를 지우려는 것이 아니다
 * - **내리는 만큼 배경이 흐려진다.** 스크롤하는 것이 창이 아니라 안쪽 영역(하단 내비 틀)이어도
 *   그 영역을 찾아 듣는다
 */
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ProfileBackdrop from './index';
import ProfileLayout from '../ProfileLayout';
import { mockMyProfileView } from '../../mocks/publicProfiles';

const PHOTO = 'https://example.com/me.jpg';

describe('사진 배경', () => {
  it('사진이 있으면 화면 배경으로 깐다', () => {
    render(
      <ProfileBackdrop photoUrl={PHOTO} top={<h1>프로필</h1>}>
        <p>본문</p>
      </ProfileBackdrop>,
    );

    const page = screen.getByTestId('profile-backdrop');
    expect(page).toHaveAttribute('data-photo', 'image');
    expect(page.style.getPropertyValue('--profile-photo')).toBe(`url("${PHOTO}")`);
    expect(screen.getByRole('heading', { name: '프로필' })).toBeInTheDocument();
    expect(screen.getByText('본문')).toBeInTheDocument();
  });

  it('사진이 없으면 어두운 단색으로 떨어진다', () => {
    render(
      <ProfileBackdrop photoUrl={null} top={null}>
        <p>본문</p>
      </ProfileBackdrop>,
    );

    const page = screen.getByTestId('profile-backdrop');
    expect(page).toHaveAttribute('data-photo', 'none');
    expect(page.style.getPropertyValue('--profile-photo')).toBe('');
  });
});

describe('동그라미 사진', () => {
  it('사진이 배경에 깔려 있으면 동그라미 사진을 또 두지 않는다', () => {
    render(
      <ProfileLayout
        mode="view"
        tone="dark"
        profile={{ ...mockMyProfileView, avatarUrl: PHOTO }}
      />,
    );

    expect(screen.queryByRole('img', { name: /프로필 이미지/ })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: mockMyProfileView.nickname })).toBeInTheDocument();
  });

  it('사진이 없는 사람은 어두운 면에서도 동그라미 자리를 남긴다', () => {
    const { container } = render(
      <ProfileLayout mode="view" tone="dark" profile={{ ...mockMyProfileView, avatarUrl: null }} />,
    );

    expect(container.querySelector('[data-placeholder="true"]')).not.toBeNull();
  });

  it('밝은 면(편집)에서는 사진이 있어도 동그라미로 보인다', () => {
    render(<ProfileLayout mode="view" profile={{ ...mockMyProfileView, avatarUrl: PHOTO }} />);

    expect(screen.getByRole('img', { name: /프로필 이미지/ })).toHaveAttribute('src', PHOTO);
  });
});

describe('스크롤', () => {
  it('안쪽 스크롤 영역을 내리는 만큼 0~1을 적는다', async () => {
    // 하단 내비 틀처럼 안쪽 영역이 스크롤한다
    render(
      <div data-testid="scroller" style={{ overflowY: 'auto', height: '400px' }}>
        <ProfileBackdrop photoUrl={PHOTO} top={null}>
          <p>본문</p>
        </ProfileBackdrop>
      </div>,
    );

    const scroller = screen.getByTestId('scroller');
    const page = screen.getByTestId('profile-backdrop');
    const progress = () => page.style.getPropertyValue('--profile-scroll');
    // 값은 requestAnimationFrame 한 번 뒤에 적힌다
    const nextFrame = () => new Promise((resolve) => requestAnimationFrame(resolve));
    const end = window.innerHeight * 0.55;

    expect(progress()).toBe('0.000');

    scroller.scrollTop = end / 2;
    fireEvent.scroll(scroller);
    await nextFrame();
    expect(Number(progress())).toBeCloseTo(0.5, 2);

    // 끝을 넘어도 1에서 멈춘다
    scroller.scrollTop = end * 3;
    fireEvent.scroll(scroller);
    await nextFrame();
    expect(progress()).toBe('1.000');
  });
});
