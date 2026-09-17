/*
 * 3.1-A ⇢ 7.6-A 한줄평 관리 (업무 S15P21E106-495)
 *
 * 306이 이 사슬을 7.6 평가 목록에서 잡고 있었는데, 업무 -495에서 7.6 화면을 지우면서
 * **7.6-A 시트를 여는 곳이 3.1-A 프레임 상세 하나만 남았다.** 그 하나가 끊기면 한줄평을
 * 고치거나 지울 자리가 앱에서 사라지므로 여기서 다시 잡는다.
 *
 * **공개 범위를 바꾸는 곳도 여기뿐이다.** 2.5 평가 화면에는 공개 선택 UI가 없어 한줄평이
 * 늘 비공개로 저장된다(CR-05).
 *
 * 프레임 상세(API-043)는 실제 호출이라 가로채고, 목업 평가 저장소의 현재 값을 읽는
 * 대역을 세운다 — 시트에서 고친 값이 다시 읽을 때 반영되는지가 이 파일이 잡으려는 자리다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import FrameDetailPage from './index';
import { mockFrameList, mockMyRatings } from '../../../mocks/frames';
import { findMockMovie } from '../../../mocks/movies';
import { resetSessionStore, saveRatingEdit, withRatingEdit } from '../../../mocks/sessionStore';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { FrameDetail } from '../../../types/taste';

vi.mock('../../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/taste')>()),
  getFrameDetail: vi.fn(),
}));

vi.mock('../../../api/watch', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/watch')>()),
  updateRating: vi.fn((ratingId, input) => {
    saveRatingEdit(ratingId, input);
    return Promise.resolve({});
  }),
}));

const { getFrameDetail } = await import('../../../api/taste');

/** 프레임 상세를 주는 서버 대역. 목업 평가 저장소의 현재 값을 읽는다 */
function frameDetail(frameId: number): Promise<FrameDetail> {
  const frame = mockFrameList.items.find((item) => item.frameId === frameId);

  if (!frame) {
    return Promise.reject(new Error('없는 프레임'));
  }

  const movie = findMockMovie(frame.movie.movieId);

  return Promise.resolve({
    frameId,
    flavorCode: frame.flavorCode,
    movie: {
      movieId: movie.movieId,
      title: movie.title,
      posterUrl: movie.posterUrl,
      releaseYear: movie.releaseYear,
      genres: movie.genres,
      directors: [],
    },
    rating: withRatingEdit(mockMyRatings[frame.movie.movieId]),
    watchRecord: { ott: null, clickedAt: null, watchedAt: null, ratedAt: frame.createdAt },
  });
}

beforeEach(() => {
  resetSessionStore();
  vi.mocked(getFrameDetail).mockReset().mockImplementation(frameDetail);
});

/** 502는 한줄평이 있는 기준 평가(영화 402)의 프레임이다 */
function renderFrame(frameId = 502) {
  return render(
    <MemoryRouter initialEntries={[`/frames/${frameId}`]}>
      <Routes>
        <Route path={ROUTE_PATTERN.frameDetail} element={<FrameDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function openSheet() {
  fireEvent.click(screen.getByRole('button', { name: '한줄평 관리' }));

  return within(screen.getByRole('dialog', { name: '한줄평 관리' }));
}

describe('3.1-A ⇢ 7.6-A', () => {
  it('한줄평을 고치고 공개 범위를 바꾸면 상세와 평가 저장소에 반영된다', async () => {
    renderFrame();

    expect(await screen.findByRole('button', { name: '한줄평 관리' })).toBeEnabled();

    const sheet = openSheet();
    const input = sheet.getByLabelText('한줄평');
    expect(input).toHaveValue('마술 트릭 하나하나가 복선이라 두 번 보게 돼요.');

    fireEvent.change(input, { target: { value: '두 번째 볼 때 더 좋았어요' } });
    expect(sheet.getByText('15/200')).toBeInTheDocument();

    // 공개 스위치는 이 시트에만 있다. 2.5는 늘 비공개로 저장한다(CR-05)
    const toggle = sheet.getByRole('switch', { name: '공개 한줄평 · 다른 사용자에게 노출' });
    expect(toggle).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(toggle);

    fireEvent.click(sheet.getByRole('button', { name: '수정 완료' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText(/두 번째 볼 때 더 좋았어요/)).toBeInTheDocument();

    expect(withRatingEdit(mockMyRatings[402])).toEqual({
      ratingId: 902,
      score: 4,
      comment: '두 번째 볼 때 더 좋았어요',
      visibility: 'PRIVATE',
      spoiler: false,
    });
  });

  it('삭제는 확인을 거쳐 한줄평만 지운다', async () => {
    renderFrame();

    await screen.findByRole('button', { name: '한줄평 관리' });
    fireEvent.click(openSheet().getByRole('button', { name: '삭제' }));

    const confirm = within(screen.getByRole('alertdialog', { name: '한줄평을 삭제할까요?' }));

    // 취소하면 아무것도 지우지 않고 시트로 돌아간다
    fireEvent.click(confirm.getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(withRatingEdit(mockMyRatings[402]).comment).not.toBeNull();

    fireEvent.click(
      within(screen.getByRole('dialog', { name: '한줄평 관리' })).getByRole('button', {
        name: '삭제',
      }),
    );
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    // 평가는 남는다. 별점도 그대로다
    expect(withRatingEdit(mockMyRatings[402])).toEqual(
      expect.objectContaining({ score: 4, comment: null }),
    );
    // 한줄평이 없으면 관리할 것이 없다
    await waitFor(() => expect(screen.getByRole('button', { name: '한줄평 관리' })).toBeDisabled());
  });
});
