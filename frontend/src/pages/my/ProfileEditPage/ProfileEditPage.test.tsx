/*
 * 7.2 프로필 편집 — 저장이 서버로 나가는지 (업무 S15P21E106-305)
 *
 * 이동표 `7.2 ⇢ 7.1`은 화면 이동만이 아니라 **바뀐 닉네임이 대시보드에 보이는 것**까지가
 * 한 경로다(screen-spec-my.md 2장 3번). 두 화면을 따로 열어 보면 저장이 끊긴 것을
 * 못 잡는다 — 편집 화면은 입력값을 그대로 들고 있어 늘 맞아 보이기 때문이다.
 *
 * **저장 위치가 바뀌었다.** 305 첫 구현은 세션 저장소에 썼는데, API-014가 실제 호출로
 * 바뀌면서(업무 -364) 로컬에만 쓰면 7.1이 옛 닉네임을 보여주게 된다. 지금은 API-015로
 * 나간다. 그래서 이 테스트도 "저장소에 남았나"가 아니라 **"무엇을 보냈나"**를 본다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import ProfileEditPage from './index';
import { renderWithRouter } from '../../../test/render';
import { ApiError } from '../../../api/apiError';
import {
  getMyIntroduction,
  getMyProfile,
  saveMyIntroduction,
  updateMyProfile,
} from '../../../api/users';
import { mockMyProfile } from '../../../mocks/user';
import { resetSessionStore } from '../../../mocks/sessionStore';

/*
 * `isValidNickname`은 진짜를 쓴다. 규칙 자체가 이 화면의 동작이라 가짜로 바꾸면
 * 무엇을 막는지 확인할 수 없다.
 */
vi.mock('../../../api/users', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/users')>();

  return {
    isValidNickname: actual.isValidNickname,
    getMyProfile: vi.fn(),
    getMyIntroduction: vi.fn(),
    updateMyProfile: vi.fn(),
    saveMyIntroduction: vi.fn(),
  };
});

const BASE_NICKNAME = mockMyProfile.nickname;
const BASE_INTRO = '반전 있는 이야기를 좋아해요';

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getMyIntroduction).mockReset().mockResolvedValue(BASE_INTRO);
  vi.mocked(updateMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(saveMyIntroduction).mockReset().mockResolvedValue(undefined);
});

async function renderEdit() {
  const result = renderWithRouter(<ProfileEditPage />, { route: '/my/profile/edit' });
  await screen.findByDisplayValue(BASE_NICKNAME);

  return result;
}

describe('7.2 프로필 편집', () => {
  it('저장하면 바뀐 닉네임을 API-015로 보낸다', async () => {
    await renderEdit();

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '난다킹' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(updateMyProfile).toHaveBeenCalledWith({ nickname: '난다킹' }));
  });

  it('저장하면 7.1로 돌아간다', async () => {
    await renderEdit();

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '난다킹' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/my'));
  });

  it('한 줄 소개는 계약에 없어 따로 저장한다', async () => {
    await renderEdit();

    fireEvent.change(screen.getByLabelText('한 줄 소개'), {
      target: { value: '느와르만 봅니다' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(saveMyIntroduction).toHaveBeenCalledWith('느와르만 봅니다'));
  });

  it('규칙에 맞지 않는 닉네임은 보내지도 이동하지도 않는다', async () => {
    await renderEdit();

    // 한 글자는 2~10자 규칙에 어긋난다
    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '난' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '닉네임은 한글 · 영문 · 숫자 · _ 2~10자예요',
    );
    expect(updateMyProfile).not.toHaveBeenCalled();
    expect(screen.getByTestId('location').textContent).toBe('/my/profile/edit');
  });

  it('서버가 거절하면 서버 문구를 그대로 보여준다', async () => {
    /*
     * 닉네임 중복은 `409 NICKNAME_DUPLICATED`다(API-015). 화면이 자체 문구로 덮으면
     * 무엇이 문제인지 사라진다.
     */
    vi.mocked(updateMyProfile).mockRejectedValue(
      new ApiError(409, 'NICKNAME_DUPLICATED', '이미 사용 중인 닉네임입니다.'),
    );

    await renderEdit();

    fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '무비버디' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 사용 중인 닉네임입니다.');
    expect(screen.getByTestId('location').textContent).toBe('/my/profile/edit');
  });

  it('연령대는 계약의 생년월일에서 만든다', async () => {
    // 계약에 연령대가 없다. API-014는 `birthDate`를 준다
    await renderEdit();

    expect(screen.getByText('20대')).toBeInTheDocument();
  });

  it('미리보기로 나가는 경로가 있다', async () => {
    await renderEdit();

    fireEvent.click(screen.getByRole('button', { name: '내 공개 프로필 미리보기' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/my/profile/preview'),
    );
  });
});
