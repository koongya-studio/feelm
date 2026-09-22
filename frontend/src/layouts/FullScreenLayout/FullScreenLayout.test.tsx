/*
 * 전체화면 화면의 껍데기 (업무 -787)
 *
 * -782는 탭 화면에만 데스크톱 셸을 세웠다. 이 업무에서 전체화면 화면도 같은 껍데기를 쓰게
 * 했으므로, **1024px 미만이 지금까지와 같은지**와 **로그인하지 않은 사람에게는 셸을 세우지
 * 않는지**를 고정한다. 뒤쪽은 없는 주소(404)가 `RequireAuth` 바깥에 있어 생기는 경우다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { useEffect, useState } from 'react';
import FullScreenLayout from './index';
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

const signedIn: AuthValue = {
  status: 'authenticated',
  user: { userId: 7, nickname: '김난다', onboardingCompleted: true },
  signIn: vi.fn(),
  signInWithSocial: vi.fn(),
  completeEmailSignup: vi.fn(),
  completeSocialSignup: vi.fn(),
  signOut: vi.fn(),
};

const anonymous: AuthValue = { ...signedIn, status: 'anonymous', user: null };

function renderShell(width: number, auth: AuthValue = signedIn) {
  setViewportWidth(width);

  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={['/movies/550']}>
        <Routes>
          <Route element={<FullScreenLayout />}>
            <Route path="/movies/:movieId" element={<p>영화 상세</p>} />
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

describe('FullScreenLayout', () => {
  describe('1024px 미만 — 지금까지의 전체화면', () => {
    it('좌측 내비도 상단 바도 건너뛰기 단추도 없다', () => {
      renderShell(412);

      expect(screen.getByText('영화 상세')).toBeInTheDocument();
      expect(screen.queryByRole('navigation', { name: '주요 화면' })).not.toBeInTheDocument();
      expect(screen.queryByLabelText('영화, 감독, 배우 검색')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '본문으로 건너뛰기' })).not.toBeInTheDocument();
    });

    it('768px 태블릿도 같다', () => {
      renderShell(768);

      expect(screen.queryByRole('navigation', { name: '주요 화면' })).not.toBeInTheDocument();
    });

    it('프로필을 받으러 가지 않는다', () => {
      renderShell(412);

      expect(getMyProfile).not.toHaveBeenCalled();
    });
  });

  describe('1024px 이상 — 탭 화면과 같은 껍데기', () => {
    it('좌측 내비와 상단 바가 선다', () => {
      renderShell(1024);

      expect(screen.getByRole('link', { name: '취향 필름' })).toBeInTheDocument();
      expect(screen.getByLabelText('영화, 감독, 배우 검색')).toBeInTheDocument();
    });

    it('화면이 본문(main)에 놓인다', () => {
      renderShell(1024);

      expect(screen.getByRole('main')).toHaveTextContent('영화 상세');
    });

    it('건너뛰기 단추가 맨 앞에 선다', () => {
      renderShell(1024);

      const skip = screen.getByRole('button', { name: '본문으로 건너뛰기' });
      const links = screen.getAllByRole('link');

      expect(skip.compareDocumentPosition(links[0])).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    });

    /*
     * 없는 주소(404)는 로그인 없이도 닿는다. 좌측 내비는 로그인해야 갈 수 있는 곳만 가리키므로
     * 세우면 누를 때마다 로그인 화면으로 튕기는 메뉴가 된다.
     */
    it('로그인하지 않았으면 셸을 세우지 않는다', () => {
      renderShell(1440, anonymous);

      expect(screen.getByText('영화 상세')).toBeInTheDocument();
      expect(screen.queryByRole('navigation', { name: '주요 화면' })).not.toBeInTheDocument();
      expect(screen.queryByLabelText('영화, 감독, 배우 검색')).not.toBeInTheDocument();
    });
  });

  /*
   * 창을 끌어 폭이 1024px 경계를 지나면 껍데기가 통째로 갈리고 화면이 다시 그려진다. 2.4 영화
   * 상세처럼 그릴 때 데이터를 받아 오는 화면은 그때 받던 것을 버리고 다시 받아야 하며, 다시
   * 받지 않으면 **내용 없는 빈 화면이 남는다**.
   *
   * 2026-09-22 헤드리스 캡처에서 폭 1024px일 때 빈 화면이 찍혀 확인한 자리다(업무 -787).
   * 같은 증상이 이 업무에서 건드리지 않은 탭 화면에서도 나와, 갈림 자체가 데이터를 잃게 하는지
   * 여기서 고정한다.
   */
  describe('폭이 1024px 경계를 지나 껍데기가 갈릴 때', () => {
    /** 그릴 때 데이터를 받아 오는 화면. 받아 오는 것을 시험이 직접 끝내 준다 */
    function LoadingPage({ onLoad }: { onLoad: () => Promise<string> }) {
      const [data, setData] = useState<string | null>(null);

      useEffect(() => {
        let active = true;

        void onLoad().then((value) => {
          if (active) setData(value);
        });

        return () => {
          active = false;
        };
      }, [onLoad]);

      return <p>{data ?? '받는 중'}</p>;
    }

    function renderLoading(width: number, onLoad: () => Promise<string>) {
      setViewportWidth(width);

      return render(
        <AuthContext.Provider value={signedIn}>
          <MemoryRouter initialEntries={['/movies/550']}>
            <Routes>
              <Route element={<FullScreenLayout />}>
                <Route path="/movies/:movieId" element={<LoadingPage onLoad={onLoad} />} />
              </Route>
            </Routes>
          </MemoryRouter>
        </AuthContext.Provider>,
      );
    }

    it('받는 중에 갈려도 다시 받아 결국 내용이 나온다', async () => {
      const settle: ((value: string) => void)[] = [];
      const onLoad = () => new Promise<string>((resolve) => settle.push(resolve));

      renderLoading(412, onLoad);
      expect(screen.getByText('받는 중')).toBeInTheDocument();
      expect(settle).toHaveLength(1);

      setViewportWidth(1280);
      await waitFor(() => expect(screen.getByRole('main')).toBeInTheDocument());

      // 갈린 뒤 다시 받으러 갔다
      expect(settle.length).toBeGreaterThan(1);

      settle.forEach((resolve) => resolve('영화 제목'));
      await waitFor(() => expect(screen.getByText('영화 제목')).toBeInTheDocument());
    });

    it('다 받은 뒤에 갈려도 내용이 남는다', async () => {
      const settle: ((value: string) => void)[] = [];
      const onLoad = () => new Promise<string>((resolve) => settle.push(resolve));

      renderLoading(412, onLoad);
      settle.forEach((resolve) => resolve('영화 제목'));
      await screen.findByText('영화 제목');

      setViewportWidth(1280);
      await waitFor(() => expect(screen.getByRole('main')).toBeInTheDocument());

      settle.forEach((resolve) => resolve('영화 제목'));
      await waitFor(() => expect(screen.getByText('영화 제목')).toBeInTheDocument());
    });
  });
});
