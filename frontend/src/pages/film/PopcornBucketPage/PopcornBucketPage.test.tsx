/*
 * 3.2 100알의 취향 — 맛 설명 (업무 S15P21E106-631)
 *
 * 맛 이름만으로는 `오리지널 솔트`가 어떤 영화인지 알 수 없다는 QA 지적이다. 계약에 설명
 * 문장이 없어 범례의 맛마다 기준정보(API-044)의 대표 키워드를 한 줄로 단다.
 *
 * 설명은 곁들이는 정보다. 기준정보를 받지 못해도 범례와 수치는 그대로 그려야 한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import PopcornBucketPage from './index';
import { renderWithRouter } from '../../../test/render';
import type { PopcornBucket, PopcornBucketFlavor } from '../../../types/taste';

vi.mock('../../../api/taste', () => ({
  getPopcornBucket: vi.fn(),
  getPopcornFlavors: vi.fn(),
}));

const { getPopcornBucket, getPopcornFlavors } = await import('../../../api/taste');

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

/** 실제 배포 기준정보 중 두 맛이다 */
const flavors = [
  {
    code: 'FLAVOR_A',
    displayName: '오리지널 솔트',
    color: '#F0D8B4',
    representativeKeywords: ['다큐멘터리', '현실 기록', '음악', '역사'],
    sortOrder: 1,
  },
  {
    code: 'FLAVOR_D',
    displayName: '피스타치오',
    color: '#B4B46C',
    representativeKeywords: ['드라마', '인물', '관계', '역사', '전쟁'],
    sortOrder: 4,
  },
];

/** 범례의 한 맛 줄 */
function legendRow(name: string) {
  const row = screen.getByText(name).closest('li');

  if (!row) throw new Error(`${name} 범례 줄이 없다`);

  return within(row);
}

beforeEach(() => {
  vi.mocked(getPopcornBucket).mockReset().mockResolvedValue(bucket);
  vi.mocked(getPopcornFlavors).mockReset().mockResolvedValue(flavors);
});

describe('3.2 맛 설명', () => {
  it('범례의 맛마다 무엇을 뜻하는지 대표 키워드를 단다', async () => {
    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('다큐멘터리 · 현실 기록 · 음악 · 역사')).toBeInTheDocument();

    // 설명은 제 맛 줄에 붙는다. 다른 맛의 키워드와 섞이지 않는다
    expect(
      legendRow('오리지널 솔트').getByText('다큐멘터리 · 현실 기록 · 음악 · 역사'),
    ).toBeInTheDocument();
    expect(
      legendRow('피스타치오').getByText('드라마 · 인물 · 관계 · 역사 · 전쟁'),
    ).toBeInTheDocument();
    expect(legendRow('피스타치오').getByText('17')).toBeInTheDocument();
  });

  it('기준정보를 받지 못해도 범례와 수치는 그대로 그리고 설명 줄만 없다', async () => {
    vi.mocked(getPopcornFlavors).mockRejectedValue(new Error('boom'));

    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('오리지널 솔트')).toBeInTheDocument();
    await waitFor(() => expect(getPopcornFlavors).toHaveBeenCalled());

    expect(legendRow('피스타치오').getByText('17')).toBeInTheDocument();
    expect(screen.queryByText(/다큐멘터리/)).toBeNull();
    expect(screen.queryByText('팝콘 버킷을 불러오지 못했어요')).toBeNull();
  });
});

/*
 * 알 수(감상량)와 별점(선호도)의 뜻 (업무 S15P21E106-677, UI/UX 분석 리포트 P1-03).
 * 구분이 FR-06 주석에만 있어 범례의 두 숫자가 무엇인지 화면에서 알 수 없었다.
 */
describe('3.2 알 수와 별점의 뜻', () => {
  it('핵심 수치를 `팝콘 N알`로 적고 두 숫자의 뜻을 한 줄로 푼다', async () => {
    renderWithRouter(<PopcornBucketPage />);

    expect(await screen.findByText('팝콘 22알')).toBeInTheDocument();
    expect(screen.queryByText(/KERNELS/)).toBeNull();
    expect(screen.getByText(/영화를 평가하면 그 영화의 맛 알이 하나씩 쌓여요/)).toHaveTextContent(
      '★는 그 맛 영화들에 준 평균 별점이에요',
    );
  });
});
