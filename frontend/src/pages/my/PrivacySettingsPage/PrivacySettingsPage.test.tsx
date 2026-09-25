/*
 * 7.8-B 공개 범위 설정 — 누름 반응과 저장 경과 (업무 S15P21E106-854)
 *
 * 나갔다 들어와도 값이 남는지는 `SettingsFlow.test.tsx`가 본다. 여기서는 **응답이 오기
 * 전**을 본다. 저장하는 동안 칸을 잠갔더니 그 사이의 탭이 말없이 사라져 눌러도 반응하지
 * 않는 것처럼 보였다. 응답을 시험이 직접 풀어 주도록 API-015를 붙잡아 둔다.
 *
 * 고정할 것
 * 1. 한 번 누르면 곧바로 고른 칸이 바뀌고, 저장이 끝나면 그 값이 남는다
 * 2. 저장 중에도 칸은 잠기지 않는다. 요청은 한 번에 하나이고, 같은 줄을 거듭 누르면
 *    마지막 값이 저장된다
 * 3. 실패하면 그 줄만 서버가 가진 값으로 돌아가고 곧바로 알린다. 알림은 그 줄을 다시 저장해
 *    성공할 때까지 남는다 — 다른 줄을 누르거나 저장해도 거두지 않는다
 * 4. 저장 중 · 저장됨을 눈과 읽기 프로그램 모두에 알린다. 보내는 동안 원래 값으로 되돌려도
 *    요청이 끝날 때까지는 저장 중이다
 * 5. 이 화면에서 고른 줄만 보낸다. 응답에서 다른 줄이 바뀌어 있으면(다른 탭 · 기기) 그 값을
 *    따르고 덮어쓰지 않는다
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import PrivacySettingsPage from './index';
import { getMyProfile, updateMyProfile } from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';
import type { MyProfile, MyProfileUpdate } from '../../../types/user';

vi.mock('../../../api/users', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/users')>()),
  getMyProfile: vi.fn(),
  updateMyProfile: vi.fn(),
}));

/** 서버가 가진 값. 성공한 요청만 여기에 쓴다 */
let serverProfile: MyProfile;

/** 아직 답하지 않은 API-015 요청. 시험이 하나씩 풀어 준다 */
let pending: { update: MyProfileUpdate; succeed: () => void; fail: () => void }[];

beforeEach(() => {
  serverProfile = { ...mockMyProfile };
  pending = [];

  vi.mocked(getMyProfile)
    .mockReset()
    .mockImplementation(() => Promise.resolve({ ...serverProfile }));
  vi.mocked(updateMyProfile)
    .mockReset()
    .mockImplementation(
      (update) =>
        new Promise<MyProfile>((resolve, reject) => {
          pending.push({
            update,
            succeed: () => {
              Object.assign(serverProfile, update);
              resolve({ ...serverProfile });
            },
            fail: () => reject(new Error('서버 오류')),
          });
        }),
    );
});

async function renderPage() {
  render(
    <MemoryRouter>
      <PrivacySettingsPage />
    </MemoryRouter>,
  );

  await screen.findByRole('radiogroup', { name: '프로필 공개' });
}

const group = (name: string) => screen.getByRole('radiogroup', { name });
const radio = (groupName: string, name: string) =>
  within(group(groupName)).getByRole('radio', { name });

/** 가장 먼저 온 요청에 답한다 */
async function answer(result: 'succeed' | 'fail') {
  const request = pending.shift();

  if (!request) throw new Error('답할 요청이 없다');

  await act(async () => {
    request[result]();
  });
}

describe('7.8-B 공개 범위 선택', () => {
  it('한 번 누르면 곧바로 고른 칸이 바뀌고 저장되면 그대로 남는다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '비공개'));

    // 응답을 기다리지 않고 고른 칸이 선다
    expect(radio('프로필 공개', '비공개')).toHaveAttribute('aria-checked', 'true');
    expect(radio('프로필 공개', '전체 공개')).toHaveAttribute('aria-checked', 'false');
    expect(pending.map((request) => request.update)).toEqual([{ visibility: 'PRIVATE' }]);

    // 저장 중을 눈과 읽기 프로그램에 알린다
    expect(group('프로필 공개')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('저장 중')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('저장하고 있어요');

    await answer('succeed');

    expect(radio('프로필 공개', '비공개')).toHaveAttribute('aria-checked', 'true');
    expect(group('프로필 공개')).not.toHaveAttribute('aria-busy');
    expect(screen.queryByText('저장 중')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('저장했어요');
    expect(serverProfile.visibility).toBe('PRIVATE');

    // 다시 전체 공개로 돌리는 것도 한 번이다
    fireEvent.click(radio('프로필 공개', '전체 공개'));
    expect(radio('프로필 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    await answer('succeed');
    expect(serverProfile.visibility).toBe('PUBLIC');
    expect(updateMyProfile).toHaveBeenCalledTimes(2);
  });

  it('이미 고른 칸을 다시 눌러도 요청을 보내지 않는다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '전체 공개'));

    expect(updateMyProfile).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('저장 중에 같은 줄을 거듭 누르면 칸은 잠기지 않고 마지막 값이 저장된다', async () => {
    await renderPage();

    fireEvent.click(radio('취향 필름 공개', '비공개'));
    // 저장 중에도 누를 수 있다. 누른 값이 곧바로 선다
    expect(radio('취향 필름 공개', '전체 공개')).toBeEnabled();
    fireEvent.click(radio('취향 필름 공개', '전체 공개'));
    fireEvent.click(radio('취향 필름 공개', '비공개'));
    fireEvent.click(radio('취향 필름 공개', '전체 공개'));

    expect(radio('취향 필름 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    // 요청은 겹치지 않는다. 첫 요청이 끝나기 전에는 하나뿐이다
    expect(pending).toHaveLength(1);

    await answer('succeed');

    // 서버는 PRIVATE가 됐지만 마지막으로 고른 것은 전체 공개라 이어서 보낸다
    expect(radio('취향 필름 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('저장하고 있어요');
    expect(pending.map((request) => request.update)).toEqual([{ frameVisibility: 'PUBLIC' }]);

    await answer('succeed');

    expect(serverProfile.frameVisibility).toBe('PUBLIC');
    expect(radio('취향 필름 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('저장했어요');
    // 중간값 두 번은 보내지 않았다
    expect(updateMyProfile).toHaveBeenCalledTimes(2);
  });

  it('보내는 동안 원래 값으로 되돌려도 요청이 끝날 때까지 저장 중이고 되돌린 값을 이어서 보낸다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '비공개'));
    fireEvent.click(radio('프로필 공개', '전체 공개'));

    // 화면은 서버와 같은 값이 됐지만 첫 요청은 아직 남아 있다
    expect(radio('프로필 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    expect(group('프로필 공개')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('저장 중')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('저장하고 있어요');

    await answer('succeed');

    // 서버가 PRIVATE가 됐으니 되돌린 값을 보낸다. 그동안에도 저장 중이다
    expect(pending.map((request) => request.update)).toEqual([{ visibility: 'PUBLIC' }]);
    expect(group('프로필 공개')).toHaveAttribute('aria-busy', 'true');

    await answer('succeed');

    expect(serverProfile.visibility).toBe('PUBLIC');
    expect(group('프로필 공개')).not.toHaveAttribute('aria-busy');
    expect(screen.queryByText('저장 중')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('저장했어요');
  });

  it('저장 중에 다른 줄을 누르면 그 줄도 곧바로 바뀌고 차례로 저장된다', async () => {
    await renderPage();

    fireEvent.click(radio('팝콘 버킷 공개', '비공개'));
    fireEvent.click(radio('한줄평 공개', '비공개'));

    expect(radio('한줄평 공개', '비공개')).toHaveAttribute('aria-checked', 'true');
    expect(group('팝콘 버킷 공개')).toHaveAttribute('aria-busy', 'true');
    expect(group('한줄평 공개')).toHaveAttribute('aria-busy', 'true');
    expect(pending.map((request) => request.update)).toEqual([{ popcornVisibility: 'PRIVATE' }]);

    await answer('succeed');

    expect(group('팝콘 버킷 공개')).not.toHaveAttribute('aria-busy');
    expect(pending.map((request) => request.update)).toEqual([{ reviewVisibility: 'PRIVATE' }]);

    await answer('succeed');

    expect(serverProfile.popcornVisibility).toBe('PRIVATE');
    expect(serverProfile.reviewVisibility).toBe('PRIVATE');
    // 보내지 않은 줄은 그대로다
    expect(serverProfile.visibility).toBe('PUBLIC');
    expect(serverProfile.frameVisibility).toBe('PUBLIC');
  });

  it('저장하지 못하면 그 줄만 서버 값으로 돌아가고 알린다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '비공개'));
    fireEvent.click(radio('한줄평 공개', '비공개'));

    await answer('fail');

    expect(radio('프로필 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    expect(group('프로필 공개')).not.toHaveAttribute('aria-busy');
    // 다른 줄이 저장되기를 기다리지 않고 알린다
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했어요');
    // 다른 줄은 제 차례에 저장된다
    expect(radio('한줄평 공개', '비공개')).toHaveAttribute('aria-checked', 'true');

    await answer('succeed');

    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했어요');
    expect(screen.getByRole('status')).not.toHaveTextContent('저장했어요');
    expect(serverProfile.visibility).toBe('PUBLIC');
    expect(serverProfile.reviewVisibility).toBe('PRIVATE');

    // 다시 누르면 새로 저장하고, 성공한 뒤에야 알림을 거둔다
    fireEvent.click(radio('프로필 공개', '비공개'));
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했어요');
    await answer('succeed');
    await waitFor(() => expect(serverProfile.visibility).toBe('PRIVATE'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('저장했어요');
  });

  it('실패한 뒤 다른 줄을 누르고 모두 저장돼도 실패 알림이 사라지지 않는다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '비공개'));
    fireEvent.click(radio('한줄평 공개', '비공개'));

    await answer('fail');
    // 한줄평이 저장되는 사이에 취향 필름을 고른다
    fireEvent.click(radio('취향 필름 공개', '비공개'));
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했어요');

    await answer('succeed');
    await answer('succeed');

    expect(pending).toHaveLength(0);
    expect(serverProfile).toMatchObject({
      visibility: 'PUBLIC',
      frameVisibility: 'PRIVATE',
      reviewVisibility: 'PRIVATE',
    });
    expect(radio('프로필 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    // 프로필은 저장되지 않았으니 알림이 남고, 저장했어요라고 말하지 않는다
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했어요');
    expect(screen.getByRole('status')).not.toHaveTextContent('저장했어요');

    // 다른 줄을 더 눌러 저장해도 마찬가지다
    fireEvent.click(radio('팝콘 버킷 공개', '비공개'));
    await answer('succeed');
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했어요');
    expect(screen.getByRole('status')).not.toHaveTextContent('저장했어요');
  });

  it('실패한 줄을 다른 줄이 저장되는 사이에 다시 고르면 성공 후 알림이 남지 않는다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '비공개'));
    fireEvent.click(radio('한줄평 공개', '비공개'));

    await answer('fail');
    // 한줄평이 저장되는 사이에 프로필을 다시 고른다
    fireEvent.click(radio('프로필 공개', '비공개'));

    await answer('succeed');
    await answer('succeed');

    expect(serverProfile.visibility).toBe('PRIVATE');
    expect(serverProfile.reviewVisibility).toBe('PRIVATE');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('저장했어요');
  });

  it('보내는 동안 다시 골랐어도 실패하면 서버가 가진 값으로 돌아간다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '비공개'));
    fireEvent.click(radio('프로필 공개', '전체 공개'));
    fireEvent.click(radio('프로필 공개', '비공개'));

    await answer('fail');

    // 화면이 서버와 어긋난 채 남지 않는다. 더 보내지도 않는다
    expect(radio('프로필 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    expect(pending).toHaveLength(0);
    expect(screen.getByRole('alert')).toHaveTextContent('저장하지 못했어요');
    expect(serverProfile.visibility).toBe('PUBLIC');
  });

  it('다른 곳에서 바꾼 줄이 응답에 담겨 오면 그 값을 따르고 되돌려 보내지 않는다', async () => {
    await renderPage();

    // 이 화면을 연 뒤 다른 탭에서 취향 필름을 비공개로 저장했다
    serverProfile.frameVisibility = 'PRIVATE';

    fireEvent.click(radio('프로필 공개', '비공개'));
    await answer('succeed');

    // 건드리지 않은 줄은 응답의 값을 보이고, 전체 공개로 되돌리는 요청을 보내지 않는다
    expect(radio('취향 필름 공개', '비공개')).toHaveAttribute('aria-checked', 'true');
    expect(group('취향 필름 공개')).not.toHaveAttribute('aria-busy');
    expect(pending).toHaveLength(0);
    expect(updateMyProfile).toHaveBeenCalledTimes(1);
    expect(serverProfile.frameVisibility).toBe('PRIVATE');
    expect(screen.getByRole('status')).toHaveTextContent('저장했어요');
  });

  it('차례를 기다리는 줄은 다른 곳에서 바뀌었어도 이 화면에서 고른 값을 보낸다', async () => {
    await renderPage();

    fireEvent.click(radio('프로필 공개', '비공개'));
    fireEvent.click(radio('한줄평 공개', '비공개'));
    // 다른 탭에서 한줄평과 팝콘 버킷을 바꿨다
    serverProfile.reviewVisibility = 'PRIVATE';
    serverProfile.popcornVisibility = 'PRIVATE';

    await answer('succeed');

    // 한줄평은 서버가 이미 고른 값을 가져 더 보낼 것이 없다. 팝콘 버킷은 응답을 따른다
    expect(pending).toHaveLength(0);
    expect(radio('한줄평 공개', '비공개')).toHaveAttribute('aria-checked', 'true');
    expect(radio('팝콘 버킷 공개', '비공개')).toHaveAttribute('aria-checked', 'true');

    // 다른 탭이 전체 공개로 되돌려도, 이 화면에서 고른 줄은 그 값을 보낸다
    serverProfile.popcornVisibility = 'PUBLIC';
    fireEvent.click(radio('프로필 공개', '전체 공개'));
    fireEvent.click(radio('취향 필름 공개', '비공개'));
    serverProfile.frameVisibility = 'PUBLIC';
    await answer('succeed');

    expect(radio('팝콘 버킷 공개', '전체 공개')).toHaveAttribute('aria-checked', 'true');
    expect(radio('취향 필름 공개', '비공개')).toHaveAttribute('aria-checked', 'true');
    expect(pending.map((request) => request.update)).toEqual([{ frameVisibility: 'PRIVATE' }]);

    await answer('succeed');

    expect(serverProfile).toMatchObject({
      visibility: 'PUBLIC',
      frameVisibility: 'PRIVATE',
      popcornVisibility: 'PUBLIC',
      reviewVisibility: 'PRIVATE',
    });
    expect(updateMyProfile).toHaveBeenCalledTimes(3);
  });
});
