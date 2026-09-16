/*
 * 3.1 취향 필름 메인 — 분기 테스트 (업무 S15P21E106-333)
 *
 * 이 화면은 두 계약을 각각 다르게 다룬다. 프레임 목록(API-042)이 실패하면 화면 전체가
 * 실패 상태가 되지만, 팝콘 버킷(API-041)이 실패하면 범례 자리만 빈다. 그 비대칭이
 * 의도된 것임을 고정한다.
 *
 * 범례는 알이 많은 순 네 개이고 0알인 맛은 빠진다. 화면에서 계산하는 값이라
 * (계약에 정렬 규칙이 없다) 회귀가 나기 쉽다.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import FilmPage from './index';
import { renderWithRouter } from '../../../test/render';
import type { FrameList, PopcornBucket, PopcornBucketFlavor } from '../../../types/taste';

vi.mock('../../../api/taste', () => ({
  getMyFrames: vi.fn(),
  getPopcornBucket: vi.fn(),
}));

const { getMyFrames, getPopcornBucket } = await import('../../../api/taste');

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

function frameList(totalCount: number, count = totalCount): FrameList {
  return {
    totalCount,
    items: Array.from({ length: count }, (_, index) => ({
      frameId: index + 1,
      movie: { movieId: 100 + index, title: `영화 ${index + 1}`, posterUrl: null },
      score: 4,
      flavorCode: 'FLAVOR_CAPER',
      createdAt: '2026-09-01T00:00:00Z',
    })),
    nextCursor: null,
    hasNext: false,
  };
}

/** 알 수를 일부러 뒤섞어 둔다. 정렬을 하지 않으면 이 순서가 그대로 나온다 */
const bucket: PopcornBucket = {
  totalCount: 100,
  flavors: [
    flavor('FLAVOR_CAPER', '기본', 12),
    flavor('FLAVOR_THRILL', '솔티드 카라멜', 28),
    flavor('FLAVOR_NOIR', '체다 치즈', 0),
    flavor('FLAVOR_CRIME', '더블 초콜릿', 21),
    flavor('FLAVOR_ACTION', '쿠키 앤 크림', 5),
    flavor('FLAVOR_DRAMA', '비스코프', 34),
    flavor('FLAVOR_COMEDY', '딸기', 0),
    flavor('FLAVOR_SF', '블루베리', 0),
  ],
};

beforeEach(() => {
  vi.mocked(getMyFrames).mockReset();
  vi.mocked(getPopcornBucket).mockReset();
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

    expect(await screen.findByText('아직 감긴 프레임이 없어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '추천 영화 보기' })).toBeInTheDocument();
  });

  it('팝콘 범례를 알이 많은 순 네 개로 두고 0알인 맛은 뺀다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(3));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);

    renderWithRouter(<FilmPage />);

    /*
     * 범례는 `<li>` 안에 `{맛 이름} {알 수}`를 텍스트로 붙인다. 목록을 순서대로 읽어
     * 비교한다 — 이름 하나만 찾는 것보다 정렬을 직접 확인한다.
     */
    const panel = await screen.findByText(/KERNELS/);
    const bucketPanel = panel.closest('button');

    if (bucketPanel === null) {
      throw new Error('팝콘 범례 판을 찾지 못했다');
    }

    await waitFor(() => {
      expect(bucketPanel.querySelectorAll('li').length).toBeGreaterThan(0);
    });

    const rows = [...bucketPanel.querySelectorAll('li')].map((li) =>
      li.textContent?.replace(/\s+/g, ' ').trim(),
    );

    // 34 · 28 · 21 · 12 순. 0알인 세 맛은 빠지고, 5알은 네 개를 넘겨 잘린다
    expect(rows).toEqual(['비스코프 34', '솔티드 카라멜 28', '더블 초콜릿 21', '기본 12']);
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
    expect(screen.queryByText('기본')).not.toBeInTheDocument();
  });

  it('헤더의 검색 링크가 검색 화면으로 간다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue(frameList(3));
    vi.mocked(getPopcornBucket).mockResolvedValue(bucket);

    renderWithRouter(<FilmPage />);

    const search = await screen.findByLabelText('검색');

    expect(search).toHaveAttribute('href', '/search');
  });
});
