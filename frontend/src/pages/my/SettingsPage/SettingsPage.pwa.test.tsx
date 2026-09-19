import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AuthContext } from '../../../contexts/authContext';
import { PwaInstallContext } from '../../../pwa/installContext';
import SettingsPage from '.';
import type { AuthValue } from '../../../contexts/authContext';
import type { PwaInstallValue } from '../../../pwa/installContext';

const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: 1024, nickname: '김난다', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

function renderSettings(pwa: PwaInstallValue) {
  return render(
    <AuthContext.Provider value={auth}>
      <PwaInstallContext.Provider value={pwa}>
        <MemoryRouter>
          <SettingsPage />
        </MemoryRouter>
      </PwaInstallContext.Provider>
    </AuthContext.Provider>,
  );
}

describe('7.8 PWA 설치', () => {
  it('브라우저 설치 프롬프트는 설정의 사용자 동작으로 연다', () => {
    const install = vi.fn().mockResolvedValue('accepted');
    renderSettings({ availability: 'prompt', install });

    fireEvent.click(screen.getByRole('button', { name: /Feelm 앱 설치/ }));

    expect(install).toHaveBeenCalledTimes(1);
  });

  it('iOS에서는 Safari 홈 화면 추가 순서를 안내한다', () => {
    renderSettings({ availability: 'ios', install: vi.fn() });

    fireEvent.click(screen.getByRole('button', { name: /Feelm 앱 설치/ }));

    const dialog = screen.getByRole('alertdialog', { name: 'Feelm을 홈 화면에 추가해 주세요' });
    expect(within(dialog).getByText(/Safari 아래쪽의 공유 버튼/)).toBeInTheDocument();
    expect(within(dialog).getByText('메뉴에서 ‘홈 화면에 추가’를 선택해요.')).toBeInTheDocument();
  });

  it('standalone 실행 중에는 설치 항목을 숨긴다', () => {
    renderSettings({ availability: 'installed', install: vi.fn() });

    expect(screen.queryByRole('button', { name: /Feelm 앱 설치/ })).toBeNull();
  });
});
