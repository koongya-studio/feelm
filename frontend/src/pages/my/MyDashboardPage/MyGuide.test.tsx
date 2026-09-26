import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { Link } from 'react-router';
import MyGuide from './MyGuide';
import DesktopNav from '../../../components/DesktopNav';
import { renderWithRouter } from '../../../test/render';
import { path } from '../../../routes/paths';
import { MY_GUIDE, hasSeenGuide, readGuideProgress, restartGuide } from '../../../utils/userGuides';

beforeEach(() => localStorage.clear());

function renderGuide(desktop = false, userId = 962) {
  const scroll = vi.fn();
  const rendered = renderWithRouter(
    <>
      {desktop ? (
        <DesktopNav account={null} />
      ) : (
        <Link to={path.settings()} data-guide-target="my-settings">
          설정
        </Link>
      )}
      <div data-guide-target="my-records" role="tablist" aria-label="내 기록" />
      <MyGuide userId={userId} desktop={desktop} />
    </>,
    { route: path.my() },
  );
  const settings = screen.getByRole('link', { name: '설정' });
  settings.scrollIntoView = scroll;
  return { ...rendered, scroll };
}

it.each([false, true])(
  '모바일/데스크톱 실제 설정 진입점을 강조하고 설정으로 이동한다 (%s)',
  (desktop) => {
    const { scroll } = renderGuide(desktop);
    expect(screen.getByRole('status', { name: '앱 사용 안내 1/2 단계' })).toHaveTextContent(
      '내 기록 활용',
    );
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getByRole('status', { name: '앱 사용 안내 2/2 단계' })).toHaveTextContent(
      '공개 범위',
    );
    expect(scroll).toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '설정 둘러보기' }));
    expect(screen.getByTestId('location')).toHaveTextContent(path.settings());
    expect(hasSeenGuide(962, MY_GUIDE)).toBe(true);
    expect(readGuideProgress(962, MY_GUIDE)).toBeNull();
  },
);

it('재진입은 두 번째 단계에서 이어지고 나중에를 선택하면 다시 나타나지 않는다', () => {
  const first = renderGuide();
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  first.unmount();
  const second = renderGuide();
  expect(screen.getByRole('status', { name: '앱 사용 안내 2/2 단계' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '나중에' }));
  second.unmount();
  renderGuide();
  expect(screen.queryByRole('status')).toBeNull();
});

it('건너뛰기는 계정별로 완료하고 다시 보기 후 첫 단계로 돌아간다', () => {
  const first = renderGuide();
  fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }));
  expect(hasSeenGuide(962, MY_GUIDE)).toBe(true);
  first.unmount();
  const another = renderGuide(false, 963);
  expect(screen.getByText('내 기록 활용')).toBeInTheDocument();
  another.unmount();
  restartGuide(962, MY_GUIDE);
  renderGuide();
  expect(screen.getByRole('status', { name: '앱 사용 안내 1/2 단계' })).toBeInTheDocument();
});
