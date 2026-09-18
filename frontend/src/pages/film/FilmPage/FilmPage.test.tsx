/*
 * 3.1 취향 필름 메인 — 분기 테스트 (업무 S15P21E106-333)
 *
 * 이 화면은 두 계약을 각각 다르게 다룬다. 프레임 목록(API-042)이 실패하면 화면 전체가
 * 실패 상태가 되지만, 팝콘 버킷(API-041)이 실패하면 범례 자리만 빈다. 그 비대칭이
 * 의도된 것임을 고정한다.
 *
 * 팝콘 요약은 알이 많은 순 네 개이고 0알인 맛은 빠진다. 화면에서 계산하는 값이라
 * (계약에 정렬 규칙이 없다) 회귀가 나기 쉽다.
 *
 * 남은 알 수(`+ 다른 맛 N알`)도 화면 계산이다. **총수에서 보여준 네 맛을 뺀 값이며,
 * 프레임 수와는 무관하다** — 대표 맛이 없는 영화는 팝콘을 만들지 않아 두 수가 어긋난다.
 *
 * 필름은 끝까지 넘기면 다음 장을 이어 받는다(업무 S15P21E106-656). 첫 장 20개만 그리던
 * 동안 `73개의 프레임을 넘겨보세요`라고 말하면서 오래된 53개에 닿을 길이 없었다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import FilmPage from './index';
import { renderWithRouter } from '../../../test/render';
import type { FrameList, PopcornBucket, PopcornBucketFlavor } from '../../../types/taste';

vi.mock('../../../api/taste', () => ({
  getMyFrames: vi.fn(),
  getPopcornBucket: vi.fn(),
}));

vi.mock('../../../api/notifications', () => ({
  getUnreadNotificationCount: vi.fn(),
}));

const { getMyFrames, getPopcornBucket } = await import('../../../api/taste');
const { getUnreadNotificationCount } = await import('../../../api/notifications');

function flavor(code: string, name: string, count: number): PopcornBucketFlavor {
  return {
    flavorCode: code,
    displayName: name,
    color: '#fbf1cf',
    popcornCount: count,
    averageScore: 4,
    discoveredAt: null,
  };
}

function frameList(
  totalCount: number,
  count = totalCount,
  { from = 1, nextCursor = null }: { from?: number; nextCursor?: string | null } = {},
): FrameList {
  return {
    totalCount,
    items: Array.from({ length: count }, (_, index) => ({
      frameId: from + index,
      movie: { movieId: 100 + from + index, title: `영화 ${from + index}`, posterUrl: null },
      score: 4,
      flavorCode: 'FLAVOR_A',
      createdAt: '2026-09-01T00:00:00Z',
    })),
    nextCursor,
    hasNext: nextCursor !== null,
  };
}

/** 필름에 그려진 프레임 수. 프레임 칸은 `영화 N 상세 보기` 버튼이다 */
function frameCount() {
  return screen.queryAllByRole('button', { name: /^영화 \d+ 상세 보기$/ }).length;
}

/** 알 수를 일부러 뒤섞어 둔다. 정렬을 하지 않으면 이 순서가 그대로 나온다 */
const bucket: PopcornBucket = {
  totalCount: 100,
  flavors: [
    flavor('FLAVOR_A', '오리지널 솔트', 12),
    flavor('FLAVOR_B', '다크초코', 28),
    flavor('FLAVOR_C', '솔티카라멜', 0),
    flavor('FLAVOR_D', '피스타치오', 21),
    flavor('FLAVOR_E', '쿠키앤크림', 5),
    flavor('FLAVOR_F', '체다치즈', 34),
    flavor('FLAVOR_G', '스트로베리', 0),
    flavor('FLAVOR_H', '블루베리', 0),
  ],
};

beforeEach(() => {
  vi.mocked(getMyFrames).mockReset();
  vi.mocked(getPopcornBucket).mockReset();
  vi.mocked(getUnreadNotificationCount).mockReset().mockResolvedValue(0);
});

describe('FilmPage', () => {
  it('프레임 목록을 불러오지 못하면 실패 상태와 다시 시도를 보여준다', async () => {
    vi.mocked(getMyFrames).mockRejectedValue(new Error('boom'));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);

    renderWithRouter(<FilmPage />);

    expect(await screen.findByText('취향 필름을 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('프레임이 0개면 빈 상태와 추천 영화 보기를 보여준다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(0, 0));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);

    renderWithRouter(<FilmPage />);

    expect(await screen.findByText('아직 필름에 담긴 영화가 없어요')).toBeInTheDocument();
    expect(screen.getByText('영화를 만나며 취향 필름을 채워보세요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '추천 영화 둘러보기' })).toBeInTheDocument();

    // 빈 상태에서도 필름은 자리를 지킨다. 채울 칸이 남았다는 것을 보여주는 화면이다
    expect(screen.getByText('첫 번째 프레임')).toBeInTheDocument();
  });

  it('팝콘 요약을 알이 많은 순 네 개로 두고 0알인 맛은 뺀다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(3));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);

    renderWithRouter(<FilmPage />);

    const kernels = await screen.findByTestId('popcorn-summary-kernels');

    // 남은 맛 수(`+ 다른 맛 N알`)가 같은 줄에 서 있으므로 맛 칸만 골라 읽는다
    const rows = [...kernels.querySelectorAll('[data-flavor]')].map((node) =>
      node.textContent?.replace(/\s+/g, ' ').trim(),
    );

    // 34 · 28 · 21 · 12 순. 0알인 세 맛은 빠지고, 5알은 네 개를 넘겨 잘린다
    expect(rows).toEqual(['체다치즈34', '다크초코28', '피스타치오21', '오리지널 솔트12']);

    // 총 100알 중 보여준 95알을 뺀 나머지. 프레임 3개와 무관한 수다
    expect(screen.getByText('+ 다른 맛 5알')).toBeInTheDocument();
  });

  it('팝콘 버킷만 실패하면 화면은 살아 있고 범례 자리만 빈다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(3));
    vi.mocked(getPopcornBucket).mockRejectedValue(new Error('boom'));

    renderWithRouter(<FilmPage />);

    // 실패 상태로 넘어가지 않는다. 이 화면의 본문은 필름 목록이다
    await waitFor(() => {
      expect(screen.getByLabelText('검색')).toBeInTheDocument();
    });
    expect(screen.queryByText('취향 필름을 불러오지 못했어요')).not.toBeInTheDocument();
    expect(screen.queryByTestId('popcorn-summary-kernels')).not.toBeInTheDocument();
  });

  it('헤더의 검색 링크가 검색 화면으로 간다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(3));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);

    renderWithRouter(<FilmPage />);

    const search = await screen.findByLabelText('검색');

    expect(search).toHaveAttribute('href', '/search');
  });

  it('미확인 알림이 있으면 헤더 링크의 이름과 점으로 알린다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(3));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);
    vi.mocked(getUnreadNotificationCount).mockResolvedValue(2);

    renderWithRouter(<FilmPage />);

    expect(await screen.findByRole('link', { name: '알림, 읽지 않은 알림 2개' })).toHaveAttribute(
      'href',
      '/notifications',
    );
  });

  /*
   * 3.1 → 2.2 (업무 -461)
   *
   * 홈에서 오늘의 추천을 빼면서 **프레임이 쌓인 사용자에게는 여기가 2.2로 가는 유일한
   * 상시 진입점이 됐다.** 전에는 프레임 0개인 빈 상태에만 있던 길이다. 이 버튼이
   * 사라지면 추천 화면이 앱에서 고아가 되므로 시험으로 잡는다.
   */
  it('프레임이 있어도 필름 늘리기로 추천에 갈 수 있다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(3));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);

    renderWithRouter(<FilmPage />);

    expect(await screen.findByRole('button', { name: '필름 늘리기' })).toBeInTheDocument();
    // 빈 상태의 길은 프레임이 있으면 나오지 않는다
    expect(screen.queryByRole('button', { name: '추천 영화 둘러보기' })).not.toBeInTheDocument();
  });
});

/*
 * 다음 장 (업무 -656)
 *
 * jsdom에는 `IntersectionObserver`가 없어 다음 장 칸이 버튼이 된다. 스크롤로 잇는 길은
 * 아래 `자동으로 이어 받기`가 관찰자를 심어 따로 시험한다.
 */
describe('FilmPage 다음 장', () => {
  beforeEach(() => {
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);
  });

  it('끝의 다음 장 칸으로 나머지 프레임을 이어 받아 전부 넘겨 볼 수 있다', async () => {
    vi.mocked(getMyFrames)
      .mockResolvedValueOnce(frameList(45, 20, { nextCursor: 'c20' }))
      .mockResolvedValueOnce(frameList(45, 20, { from: 21, nextCursor: 'c40' }))
      .mockResolvedValueOnce(frameList(45, 5, { from: 41 }));

    renderWithRouter(<FilmPage />);

    expect(await screen.findByText('45개의 프레임이 감겼어요')).toBeInTheDocument();
    expect(frameCount()).toBe(20);

    fireEvent.click(screen.getByRole('button', { name: '프레임 더 보기' }));
    await waitFor(() => expect(frameCount()).toBe(40));
    expect(getMyFrames).toHaveBeenLastCalledWith('c20');

    fireEvent.click(screen.getByRole('button', { name: '프레임 더 보기' }));
    await waitFor(() => expect(frameCount()).toBe(45));
    expect(getMyFrames).toHaveBeenLastCalledWith('c40');

    // 더 받을 것이 없으면 다음 장 칸이 사라진다
    expect(screen.queryByRole('button', { name: '프레임 더 보기' })).toBeNull();
    expect(screen.getByRole('button', { name: '영화 45 상세 보기' })).toBeInTheDocument();
  });

  it('다음 장에 이미 그린 프레임이 섞여 오면 한 번만 그린다', async () => {
    // 그 사이 새 평가가 생겨 커서 경계가 밀린 경우다. 20번이 다음 장 맨 앞에 다시 온다
    vi.mocked(getMyFrames)
      .mockResolvedValueOnce(frameList(25, 20, { nextCursor: 'c20' }))
      .mockResolvedValueOnce(frameList(25, 6, { from: 20 }));

    renderWithRouter(<FilmPage />);

    fireEvent.click(await screen.findByRole('button', { name: '프레임 더 보기' }));

    await waitFor(() => expect(frameCount()).toBe(25));
    expect(screen.getAllByRole('button', { name: '영화 20 상세 보기' })).toHaveLength(1);
  });

  it('다음 장을 받지 못해도 그린 프레임은 두고 다시 불러올 수 있다', async () => {
    vi.mocked(getMyFrames)
      .mockResolvedValueOnce(frameList(30, 20, { nextCursor: 'c20' }))
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(frameList(30, 10, { from: 21 }));

    renderWithRouter(<FilmPage />);

    fireEvent.click(await screen.findByRole('button', { name: '프레임 더 보기' }));

    const retry = await screen.findByRole('button', { name: '다시 불러오기' });
    // 화면 전체가 실패 상태로 넘어가지 않는다
    expect(frameCount()).toBe(20);
    expect(screen.queryByText('취향 필름을 불러오지 못했어요')).toBeNull();

    fireEvent.click(retry);

    await waitFor(() => expect(frameCount()).toBe(30));
    expect(getMyFrames).toHaveBeenLastCalledWith('c20');
  });
});

describe('FilmPage 자동으로 이어 받기', () => {
  /** 심어 둔 관찰자들. 칸이 보인 것처럼 알린다 */
  let observers: { callback: IntersectionObserverCallback; options?: IntersectionObserverInit }[];

  beforeEach(() => {
    observers = [];
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
          observers.push({ callback, options });
        }
        observe() {}
        disconnect() {}
      },
    );
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  function reveal() {
    const { callback } = observers[observers.length - 1];

    act(() => {
      callback([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
    });
  }

  it('다음 장 칸이 릴 안에서 보일 즈음 다음 장을 부르고, 실패하면 저절로 다시 부르지 않는다', async () => {
    vi.resetModules();
    const { default: ObservedFilmPage } = await import('./index');
    const taste = await import('../../../api/taste');

    vi.mocked(taste.getMyFrames)
      .mockResolvedValueOnce(frameList(50, 20, { nextCursor: 'c20' }))
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(frameList(50, 20, { from: 21, nextCursor: 'c40' }));

    renderWithRouter(<ObservedFilmPage />);

    expect(await screen.findByText('프레임을 불러오는 중')).toBeInTheDocument();
    // 관찰 기준은 화면이 아니라 스스로 가로 스크롤하는 릴의 트랙이다
    expect(observers[observers.length - 1].options?.root).toBe(
      screen.getByRole('group', { name: '취향 필름 프레임' }),
    );

    reveal();
    expect(await screen.findByRole('button', { name: '다시 불러오기' })).toBeInTheDocument();

    // 실패 뒤에는 관찰자를 다시 걸지 않는다. 끝에 머문 채 요청이 반복되지 않는다
    const watched = observers.length;
    await act(async () => {});
    expect(observers).toHaveLength(watched);
    expect(taste.getMyFrames).toHaveBeenCalledTimes(2);

    fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }));
    await waitFor(() => expect(frameCount()).toBe(40));
  });
});
