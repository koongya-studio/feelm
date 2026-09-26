import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProfileReviews from './index';
import { getProfileReviews, getPublicProfile } from '../../api/users';
import { ApiError } from '../../api/apiError';
import { mockMyProfileView } from '../../mocks/publicProfiles';
import type { ProfileReviewPage } from '../../types/profileReviews';
vi.mock('../../api/users', () => ({ getProfileReviews: vi.fn(), getPublicProfile: vi.fn() }));
const reviews = (length: number, start = 1) =>
  Array.from({ length }, (_, i) => ({
    ratingId: i + start,
    movie: { movieId: i + start, title: `영화 ${i + start}`, posterUrl: null },
    score: 4,
    comment: `한줄평 ${i + start}`,
    spoiler: false,
    createdAt: '2026-09-20T01:00:00Z',
  }));
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(getPublicProfile).mockResolvedValue({
    ...mockMyProfileView,
    hidden: { frames: false, popcorn: false, reviews: false },
  });
});
function open(preview = false) {
  return render(
    <MemoryRouter>
      <ProfileReviews publicId="owner" count={11} preview={preview} />
    </MemoryRouter>,
  );
}
describe('프로필 공개 한줄평', () => {
  it('최대 3개 미리보기와 전체 보기 주소', async () => {
    vi.mocked(getProfileReviews).mockResolvedValue({
      items: reviews(3),
      nextCursor: 'next',
      hasNext: true,
    });
    open(true);
    expect(await screen.findByText('영화 1')).toBeInTheDocument();
    expect(getProfileReviews).toHaveBeenCalledWith('owner', undefined, 3);
    expect(screen.getByRole('link', { name: '공개 한줄평 전체 보기 (11)' })).toHaveAttribute(
      'href',
      '/users/owner/reviews',
    );
  });
  it('빈 목록과 마지막 페이지에는 전체 보기 없음', async () => {
    vi.mocked(getProfileReviews).mockResolvedValue({ items: [], nextCursor: null, hasNext: false });
    open(true);
    expect(await screen.findByText('아직 공개한 한줄평이 없어요')).toBeInTheDocument();
    expect(screen.queryByText(/전체 보기/)).toBeNull();
  });
  it('한 개는 원문과 영화 연결만 표시', async () => {
    vi.mocked(getProfileReviews).mockResolvedValue({
      items: reviews(1),
      nextCursor: null,
      hasNext: false,
    });
    open(true);
    expect(await screen.findByRole('link', { name: '영화 1' })).toHaveAttribute(
      'href',
      '/movies/1',
    );
    expect(screen.getByText('한줄평 1')).toBeInTheDocument();
    expect(screen.queryByText(/전체 보기/)).toBeNull();
  });
  it('스포일러 원문은 평가 보기 전까지 렌더하지 않는다', async () => {
    vi.mocked(getProfileReviews).mockResolvedValue({
      items: [{ ...reviews(1)[0], spoiler: true }],
      nextCursor: null,
      hasNext: false,
    });
    open(true);
    await screen.findByText('스포일러가 포함된 한줄평이에요');
    expect(screen.queryByText('한줄평 1')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '평가 보기' }));
    expect(screen.getByText('한줄평 1')).toBeInTheDocument();
  });
  it('긴 글은 펼치기와 접기', async () => {
    const scroll = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(80);
    const client = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(40);
    vi.mocked(getProfileReviews).mockResolvedValue({
      items: reviews(1),
      nextCursor: null,
      hasNext: false,
    });
    open(true);
    fireEvent.click(await screen.findByRole('button', { name: '펼치기' }));
    expect(screen.getByText('한줄평 1')).toHaveAttribute('data-expanded', 'true');
    fireEvent.click(screen.getByRole('button', { name: '접기' }));
    expect(screen.getByText('한줄평 1')).not.toHaveAttribute('data-expanded');
    scroll.mockRestore();
    client.mockRestore();
  });
  it('10개 후 추가 실패는 기존 목록 유지, 재시도 및 중복 클릭 방지', async () => {
    vi.mocked(getProfileReviews)
      .mockResolvedValueOnce({ items: reviews(10), nextCursor: 'next', hasNext: true })
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ items: reviews(11), nextCursor: null, hasNext: false });
    open();
    await screen.findByText('영화 10');
    const button = screen.getByRole('button', { name: '더 불러오기' });
    fireEvent.click(button);
    fireEvent.click(button);
    await screen.findByText('한줄평을 불러오지 못했어요');
    expect(screen.getByText('영화 10')).toBeInTheDocument();
    expect(getProfileReviews).toHaveBeenCalledTimes(2);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByText('영화 11');
    expect(screen.getAllByRole('listitem')).toHaveLength(11);
  });
  it('공개 범위 변경이나 차단 시 기존 원문 제거', async () => {
    vi.mocked(getProfileReviews).mockResolvedValue({
      items: reviews(10),
      nextCursor: 'next',
      hasNext: true,
    });
    open();
    await screen.findByText('영화 10');
    vi.mocked(getPublicProfile).mockRejectedValueOnce(
      new ApiError(404, 'RESOURCE_NOT_FOUND', '없음'),
    );
    fireEvent.click(screen.getByRole('button', { name: '더 불러오기' }));
    await screen.findByText('한줄평을 볼 수 없어요');
    expect(screen.queryByText('한줄평 1')).toBeNull();
  });
  it('더 불러오기는 이전 페이지의 개별 비공개 전환과 삭제도 제거한다', async () => {
    vi.mocked(getProfileReviews)
      .mockResolvedValueOnce({ items: reviews(10), nextCursor: 'next', hasNext: true })
      .mockResolvedValueOnce({ items: reviews(10, 2), nextCursor: null, hasNext: false });
    open();
    await screen.findByText('영화 1');
    fireEvent.click(screen.getByRole('button', { name: '더 불러오기' }));
    await screen.findByText('영화 11');
    expect(screen.queryByText('한줄평 1')).toBeNull();
    expect(getProfileReviews).toHaveBeenLastCalledWith('owner', undefined, 20);
  });
  it('기존 한줄평이 스포일러로 수정되면 다시 가린다', async () => {
    vi.mocked(getProfileReviews)
      .mockResolvedValueOnce({ items: reviews(10), nextCursor: 'next', hasNext: true })
      .mockResolvedValueOnce({
        items: [
          { ...reviews(1)[0], spoiler: true, comment: '수정한 스포일러 원문' },
          ...reviews(10, 2),
        ],
        nextCursor: null,
        hasNext: false,
      });
    open();
    await screen.findByText('한줄평 1');
    fireEvent.click(screen.getByRole('button', { name: '더 불러오기' }));
    await screen.findByText('스포일러가 포함된 한줄평이에요');
    expect(screen.queryByText('수정한 스포일러 원문')).toBeNull();
  });
  it('가린 구역은 목록을 조회하지 않는다', async () => {
    vi.mocked(getPublicProfile).mockResolvedValue({
      ...mockMyProfileView,
      hidden: { frames: false, popcorn: false, reviews: true },
    });
    open();
    await screen.findByText('비공개로 설정한 항목이에요');
    expect(getProfileReviews).not.toHaveBeenCalled();
  });
  it('최초 실패 후 재시도', async () => {
    vi.mocked(getProfileReviews)
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ items: reviews(1), nextCursor: null, hasNext: false });
    open(true);
    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));
    await screen.findByText('영화 1');
  });
  it('경로 전환 직후 이전 목록과 늦은 응답을 제거', async () => {
    let resolve!: (page: ProfileReviewPage) => void;
    vi.mocked(getProfileReviews)
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done;
          }),
      )
      .mockResolvedValueOnce({ items: reviews(1, 99), nextCursor: null, hasNext: false });
    const view = open(true);
    await waitFor(() => expect(getProfileReviews).toHaveBeenCalledOnce());
    view.rerender(
      <MemoryRouter>
        <ProfileReviews publicId="other" preview />
      </MemoryRouter>,
    );
    await screen.findByText('영화 99');
    await act(async () => resolve({ items: reviews(1), nextCursor: null, hasNext: false }));
    expect(screen.queryByText('영화 1')).toBeNull();
  });
});
