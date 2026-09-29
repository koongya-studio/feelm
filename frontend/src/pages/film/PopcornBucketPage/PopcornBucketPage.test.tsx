/*
 * 3.2 100알의 취향 — 맛 설명 (업무 S15P21E106-713)
 *
 * 맛 이름만으로는 `오리지널 솔트`가 어떤 영화인지 알 수 없다는 QA 지적이다(업무 -631).
 * -631은 기준정보(API-044)의 대표 키워드를 이어 붙였는데 업무 -473이 그 필드를 뺐고,
 * 대신 맛마다 한 문장(`description`)을 기준정보에 두기로 했다(사용자 결정 2026-09-20).
 *
 * 설명은 곁들이는 정보다. 기준정보를 받지 못해도 범례와 수치는 그대로 그려야 한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import PopcornBucketPage from './index';
import { renderWithRouter } from '../../../test/render';
import type { PopcornBucket, PopcornBucketFlavor } from '../../../types/taste';

vi.mock('../../../api/taste', () => ({
  getPopcornBucket: vi.fn(),
  getPopcornFlavors: vi.fn(),
  getMyFrames: vi.fn(),
}));

const { getMyFrames, getPopcornBucket, getPopcornFlavors } = await import('../../../api/taste');

function flavor(code: string, name: string, count: number): PopcornBucketFlavor {
  return {
    flavorCode: code,
    displayName: name,
    color: '#F0D8B4',
    popcornCount: count,
    averageScore: 4,
    discoveredAt: null,
  };
}

const bucket: PopcornBucket = {
  totalCount: 22,
  flavors: [flavor('FLAVOR_A', '오리지널 솔트', 5), flavor('FLAVOR_D', '피스타치오', 17)],
};

/** 범례의 한 맛 줄 */
function legendRow(name: string) {
  const row = screen
    .getAllByText(name)
    .map((text) => text.closest('li'))
    .find(Boolean);

  if (!row) throw new Error(`${name} 범례 줄이 없다`);

  return within(row);
}

/** 실제 배포 기준정보 중 두 맛이다 */
const flavors = [
  {
    code: 'FLAVOR_A',
    displayName: '오리지널 솔트',
    color: '#F0D8B4',
    description: '있었던 일을 그대로 담은 이야기에 끌려요',
    sortOrder: 1,
  },
  {
    code: 'FLAVOR_D',
    displayName: '피스타치오',
    color: '#B4B46C',
    description: '사람과 시대를 깊게 들여다봐요',
    sortOrder: 4,
  },
];

beforeEach(() => {
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue(bucket);
  vi.mocked(getPopcornFlavors).mockReset().mockResolvedValue(flavors);
  vi.mocked(getMyFrames).mockReset().mockResolvedValue({
    totalCount: 0,
    items: [],
    nextCursor: null,
    hasNext: false,
  });
});

describe('3.2 팝콘맛 영화', () => {
  it('맛을 누르면 그 맛의 영화만 가져오고 영화 상세로 이동한다', async () => {
    vi.mocked(getMyFrames).mockResolvedValue({
      totalCount: 1,
      items: [
        {
          frameId: 77,
          movie: { movieId: 550, title: '파이트 클럽', posterUrl: null },
          score: 4.5,
          flavorCode: 'FLAVOR_A',
          createdAt: '2026-09-01T00:00:00Z',
        },
      ],
      nextCursor: null,
      hasNext: false,
    });
    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('맛을 눌러 영화 보기 ↓')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /오리지널 솔트/ })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    fireEvent.click(await screen.findByRole('button', { name: /오리지널 솔트/ }));
    expect(screen.getByRole('button', { name: /오리지널 솔트/ })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(await screen.findByText('파이트 클럽')).toBeInTheDocument();
    expect(getMyFrames).toHaveBeenCalledWith(undefined, 'FLAVOR_A');

    fireEvent.click(screen.getByRole('link', { name: /파이트 클럽/ }));
    expect(screen.getByTestId('location')).toHaveTextContent('/movies/550');
  });

  it('다음 페이지를 이어 보여주고 맛을 닫으면 영화 목록을 숨긴다', async () => {
    vi.mocked(getMyFrames)
      .mockResolvedValueOnce({
        totalCount: 2,
        items: [
          {
            frameId: 1,
            movie: { movieId: 1, title: '첫 영화', posterUrl: null },
            score: 4,
            flavorCode: 'FLAVOR_D',
            createdAt: '2026-09-02T00:00:00Z',
          },
        ],
        nextCursor: 'next',
        hasNext: true,
      })
      .mockResolvedValueOnce({
        totalCount: 2,
        items: [
          {
            frameId: 2,
            movie: { movieId: 2, title: '다음 영화', posterUrl: null },
            score: 3.5,
            flavorCode: 'FLAVOR_D',
            createdAt: '2026-09-01T00:00:00Z',
          },
        ],
        nextCursor: null,
        hasNext: false,
      });
    renderWithRouter(<PopcornBucketPage />);

    const flavorButton = await screen.findByRole('button', { name: /피스타치오/ });
    fireEvent.click(flavorButton);
    fireEvent.click(await screen.findByRole('button', { name: '영화 더 보기' }));

    expect(await screen.findByText('다음 영화')).toBeInTheDocument();
    expect(screen.getByText('첫 영화')).toBeInTheDocument();
    expect(getMyFrames).toHaveBeenLastCalledWith('next', 'FLAVOR_D');

    fireEvent.click(flavorButton);
    expect(screen.queryByText('첫 영화')).toBeNull();
  });
});

describe('3.2 맛 설명', () => {
  it('범례의 맛마다 무엇을 뜻하는지 한 줄을 단다', async () => {
    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('있었던 일을 그대로 담은 이야기에 끌려요')).toBeInTheDocument();

    // 설명은 제 맛 줄에 붙는다. 다른 맛의 설명과 섞이지 않는다
    expect(
      legendRow('오리지널 솔트').getByText('있었던 일을 그대로 담은 이야기에 끌려요'),
    ).toBeInTheDocument();
    expect(legendRow('피스타치오').getByText('사람과 시대를 깊게 들여다봐요')).toBeInTheDocument();
    expect(legendRow('피스타치오').getByText('17알')).toBeInTheDocument();
  });

  it('기준정보를 받지 못해도 범례와 수치는 그대로 그리고 설명 줄만 없다', async () => {
    vi.mocked(getPopcornFlavors).mockRejectedValue(new Error('boom'));

    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('오리지널 솔트')).toBeInTheDocument();
    await waitFor(() => expect(getPopcornFlavors).toHaveBeenCalled());

    expect(legendRow('피스타치오').getByText('17알')).toBeInTheDocument();
    expect(screen.queryByText(/있었던 일을/)).toBeNull();
    expect(screen.queryByText('팝콘 버킷을 불러오지 못했어요')).toBeNull();
  });

  it('계약에서 빠진 대표 키워드는 그리지 않는다', async () => {
    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('오리지널 솔트')).toBeInTheDocument();
    expect(screen.queryByText(/다큐멘터리 · 현실 기록/)).toBeNull();
  });
});

/*
 * 알 수(감상량)와 별점(선호도)의 뜻 (업무 S15P21E106-677, UI/UX 분석 리포트 P1-03).
 * 구분이 FR-06 주석에만 있어 범례의 두 숫자가 무엇인지 화면에서 알 수 없었다.
 */
describe('3.2 알 수와 별점의 뜻', () => {
  it('알이 쌓이는 방식과 별점의 뜻을 범례 위에 적는다', async () => {
    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('팝콘 22알')).toBeInTheDocument();
    expect(screen.queryByText(/KERNELS/)).toBeNull();
    expect(legendRow('피스타치오').getByText('17알')).toBeInTheDocument();
    expect(
      screen.getByText('영화를 평가하면 그 영화의 맛 알이 하나씩 쌓여요.'),
    ).toBeInTheDocument();
    expect(screen.getByText('★ 평균 별점')).toBeInTheDocument();
    expect(legendRow('피스타치오').getByText('★4.0')).toBeInTheDocument();
  });
});
