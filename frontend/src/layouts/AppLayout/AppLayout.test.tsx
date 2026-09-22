/*
 * 폭에 따라 갈리는 셸 (업무 -782)
 *
 * 세 단계가 무엇을 내놓는지를 고정한다. 특히 **1024px 미만에서 지금까지와 같은지**가 중요하다 —
 * 이 업무는 데스크톱을 더하는 것이지 모바일을 바꾸는 것이 아니다.
 *
 * 우측 보조 패널은 셸이 자리만 내놓고 페이지가 내용을 보낸다. 자리가 없을 때 페이지의 내용이
 * 본문에 흘러나오지 않는지도 함께 본다 — 흘러나오면 모바일 화면 한가운데에 카드가 끼어든다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import AppLayout from './index';
import DesktopAside from '../../components/DesktopAside';
import { AuthContext } from '../../contexts/authContext';
import type { AuthValue } from '../../contexts/authContext';
import { resetViewport, setViewportWidth } from '../../test/viewport';

vi.mock('../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

vi.mock('../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

const { getUnreadNotificationCount } = await import('../../api/notifications');
const { getMyProfile } = await import('../../api/users');

const auth: AuthValue = {
  status: 'authenticated',
  user: { userId: 7, nickname: '김난다', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

/** 본문에 놓이는 화면. 우측 패널로 카드 한 장을 보낸다 */
function Page() {
  return (
    <>
      <p>필름 화면</p>
      <DesktopAside>
        <p>보조 카드</p>
      </DesktopAside>
    </>
  );
}

function renderShell(width: number) {
  setViewportWidth(width);

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/film']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/film" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

beforeEach(() => {
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
  vi.mocked(getMyProfile).mockReset().mockRejectedValue(new Error('쓰지 않는다'));
});

/*
 * **화면을 먼저 걷고 폭을 되돌린다**(업무 -789의 규칙, -787에서 여기에도 적용).
 *
 * `afterEach`는 나중에 등록한 것부터 돈다(vitest 기본값 `sequence.hooks: 'stack'`). 그래서
 * 이 자리는 `test/setup.ts`의 `cleanup()`보다 먼저 돌고, 화면이 아직 붙어 있는 채로
 * `matchMedia`가 원래대로 돌아간다. 셸은 그것을 구독하고 있으므로 걷기 전에 되돌리면
 * 남은 화면이 폭을 다시 읽는다.
 */
afterEach(() => {
  cleanup();
  resetViewport();
});

describe('AppLayout', () => {
  describe('1024px 미만 — 지금까지의 모바일 셸', () => {
    it('건너뛰기 단추를 두지 않는다', () => {
      renderShell(412);

      expect(screen.queryByRole('button', { name: '본문으로 건너뛰기' })).not.toBeInTheDocument();
    });

    it('하단 내비가 있고 좌측 내비 · 상단 바는 없다', () => {
      renderShell(412);

      expect(screen.getByRole('navigation', { name: '주요 화면' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: '필름' })).toBeInTheDocument();
      expect(screen.queryByLabelText('영화, 감독, 배우 검색')).not.toBeInTheDocument();
    });

    it('768px 태블릿도 같다', () => {
      renderShell(768);

      expect(screen.getByRole('link', { name: '필름' })).toBeInTheDocument();
      expect(screen.queryByLabelText('영화, 감독, 배우 검색')).not.toBeInTheDocument();
    });

    it('보조 패널 자리가 없으므로 페이지가 보낸 내용은 어디에도 나오지 않는다', () => {
      renderShell(412);

      expect(screen.getByText('필름 화면')).toBeInTheDocument();
      expect(screen.queryByText('보조 카드')).not.toBeInTheDocument();
    });

    it('프로필을 받으러 가지 않는다', () => {
      renderShell(412);

      expect(getMyProfile).not.toHaveBeenCalled();
    });
  });

  describe('1024px 이상 — 좌측 내비와 상단 바', () => {
    it('하단 내비 대신 좌측 내비가 선다', () => {
      renderShell(1024);

      expect(screen.getByRole('link', { name: '취향 필름' })).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: '필름' })).not.toBeInTheDocument();
      expect(screen.getByLabelText('영화, 감독, 배우 검색')).toBeInTheDocument();
    });

    it('본문이 main으로 표시된다', () => {
      renderShell(1024);

      expect(screen.getByRole('main')).toHaveTextContent('필름 화면');
    });

    /*
     * 좌측 내비가 생기면서 본문까지 Tab 11번이 됐다. 건너뛰기 단추가 그 거리를 없앤다.
     * 단추가 Tab 순서의 맨 앞이어야 의미가 있으므로 DOM에서 내비보다 앞인 것도 함께 본다.
     */
    it('맨 앞에 본문으로 건너뛰기가 있고 눌러 본문에 닿는다', () => {
      renderShell(1024);

      const skip = screen.getByRole('button', { name: '본문으로 건너뛰기' });
      const links = screen.getAllByRole('link');

      expect(skip.compareDocumentPosition(links[0])).toBe(Node.DOCUMENT_POSITION_FOLLOWING);

      fireEvent.click(skip);

      expect(screen.getByRole('main')).toHaveFocus();
    });

    it('1279px까지는 보조 패널 자리가 아직 없다', () => {
      renderShell(1279);

      expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
      expect(screen.queryByText('보조 카드')).not.toBeInTheDocument();
    });
  });

  describe('1280px 이상 — 보조 패널', () => {
    it('페이지가 보낸 내용이 보조 패널에 들어간다', () => {
      renderShell(1280);

      const aside = screen.getByRole('complementary', { name: '보조 정보' });

      expect(aside).toHaveTextContent('보조 카드');
      expect(screen.getByRole('main')).not.toHaveTextContent('보조 카드');
    });

    it('1440px도 같다', () => {
      renderShell(1440);

      expect(screen.getByRole('complementary', { name: '보조 정보' })).toHaveTextContent(
        '보조 카드',
      );
    });
  });
});
