/*
 * 2.1 홈 — `내 취향 요약`이 API-040 응답을 그린다 (업무 S15P21E106-479).
 *
 * `getMyTaste`가 목업에서 실제 호출로 바뀌었다. 목업은 늘 장르 셋과 키워드 하나를 주었지만
 * 실제 서버는 분석 캐시가 없는 사용자에게 `insufficientData: true` · 빈 배열 ·
 * `analyzedAt: null`을 준다(API-040 처리 규칙). 백엔드에 캐시를 채우는 쪽이 아직 없어 지금은
 * **모든 사용자가 그 응답을 받으므로** 그 화면이 깨지지 않는 것을 먼저 고정한다.
 *
 * - 장르와 키워드가 오면 상위 두 장르를 잇고 비율을 더해 그리고, 첫 키워드를 적는다
 * - 분석 결과가 없으면 취향 섹션만 빈 상태 안내로 서고 나머지 섹션은 그대로다
 * - 호출이 실패하면 취향 섹션이 실패 안내와 `다시 시도`로 바뀐다
 *
 * 실패 안내의 보조 문구(네트워크 단절 · 서버 실패)는 `HomePage.test.tsx`(업무 -374)가
 * 프로필 섹션으로 고정하므로 여기서는 반복하지 않는다.
 *
 * `api/*`를 모두 가로챈다. 프로필 · 파티 · OTT 기준정보도 실제 호출이라 가로채지 않으면
 * 네트워크를 두드린다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import HomePage from './index';
import { renderWithRouter } from '../../../test/render';
import { ApiError } from '../../../api/apiError';
import { getMyProfile } from '../../../api/users';
import { getOtts } from '../../../api/otts';
import { getRecommendations } from '../../../api/recommendations';
import { getAvailableNowMovies } from '../../../api/home';
import { getMyTaste } from '../../../api/taste';
import { getMyParties } from '../../../api/parties';
import { mockMyProfile } from '../../../mocks/user';
import { mockTasteAnalysis } from '../../../mocks/taste';
import type { TasteAnalysis } from '../../../types/taste';

vi.mock('../../../api/users', () => ({ getMyProfile: vi.fn() }));
vi.mock('../../../api/otts', () => ({ getOtts: vi.fn() }));
vi.mock('../../../api/recommendations', () => ({ getRecommendations: vi.fn() }));
vi.mock('../../../api/home', () => ({ getAvailableNowMovies: vi.fn() }));
vi.mock('../../../api/taste', () => ({ getMyTaste: vi.fn() }));
vi.mock('../../../api/parties', () => ({ getMyParties: vi.fn() }));
vi.mock('../../../api/reports', () => ({ getLatestReadyReport: vi.fn() }));

const TASTE_HEADING = '내 취향 요약';
const TASTE_EMPTY = '아직 취향을 요약할 만큼 평가가 쌓이지 않았어요';
const TASTE_FAILED = '취향 요약을 불러오지 못했어요';

/** 캐시가 없는 사용자에게 서버가 주는 응답. API-040 처리 규칙의 예시 그대로다 */
const noAnalysis: TasteAnalysis = {
  ratingCount: 0,
  insufficientData: true,
  analysis: { genres: [] },
  keywords: [],
  analyzedAt: null,
};

beforeEach(() => {
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getOtts).mockReset().mockResolvedValue([]);
  vi.mocked(getRecommendations)
    .mockReset()
    .mockResolvedValue({ recommendationSetId: 1, generatedAt: '2026-09-16T00:00:00Z', items: [] });
  vi.mocked(getAvailableNowMovies).mockReset().mockResolvedValue([]);
  vi.mocked(getMyTaste).mockReset().mockResolvedValue(mockTasteAnalysis);
  vi.mocked(getMyParties).mockReset().mockResolvedValue([]);
});

describe('2.1 홈 내 취향 요약', () => {
  it('장르 상위 둘을 잇고 비율을 더하며 첫 키워드를 적는다', async () => {
    renderWithRouter(<HomePage />);

    expect(await screen.findByRole('heading', { name: TASTE_HEADING })).toBeInTheDocument();
    // 액션 0.29 + 스릴러 0.2 → 49%
    expect(screen.getByText('액션 · 스릴러')).toBeInTheDocument();
    expect(screen.getByText('49%')).toBeInTheDocument();
    expect(screen.getByText(mockTasteAnalysis.keywords[0].label)).toBeInTheDocument();
    expect(screen.queryByText(TASTE_EMPTY)).toBeNull();
  });

  it('키워드 없이 장르만 와도 요약은 그린다', async () => {
    vi.mocked(getMyTaste).mockResolvedValue({ ...mockTasteAnalysis, keywords: [] });
    renderWithRouter(<HomePage />);

    expect(await screen.findByRole('heading', { name: TASTE_HEADING })).toBeInTheDocument();
    expect(screen.queryByText(mockTasteAnalysis.keywords[0].label)).toBeNull();
  });

  it('분석 결과가 없는 사용자에게는 빈 상태 안내가 서고 나머지 섹션은 그대로다', async () => {
    vi.mocked(getMyTaste).mockResolvedValue(noAnalysis);
    renderWithRouter(<HomePage />);

    expect(await screen.findByText(TASTE_EMPTY)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: TASTE_HEADING })).toBeNull();
    expect(screen.queryByText(TASTE_FAILED)).toBeNull();
    // 취향이 비어도 홈의 다른 섹션은 영향이 없다
    expect(await screen.findByText('김난다 님, 오늘은 어떤 영화를 볼까요?')).toBeInTheDocument();
    expect(await screen.findByText('참여 중인 파티가 없어요')).toBeInTheDocument();
  });

  it('호출이 실패하면 취향 섹션이 실패 안내와 다시 시도로 바뀐다', async () => {
    vi.mocked(getMyTaste)
      .mockRejectedValueOnce(
        new ApiError(503, 'SERVICE_UNAVAILABLE', '일시적으로 요청을 처리할 수 없습니다.'),
      )
      .mockResolvedValue(mockTasteAnalysis);
    renderWithRouter(<HomePage />);

    expect(await screen.findByText(TASTE_FAILED)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: TASTE_HEADING })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByRole('heading', { name: TASTE_HEADING })).toBeInTheDocument();
    expect(screen.queryByText(TASTE_FAILED)).toBeNull();
    expect(getMyTaste).toHaveBeenCalledTimes(2);
  });
});
