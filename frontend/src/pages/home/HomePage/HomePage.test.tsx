/*
 * 2.1 홈 — 연동된 영역의 실패 표시 (업무 S15P21E106-374)
 *
 * 인사말은 프로필(API-014)이 실제 호출이라 백엔드가 없으면 실패한다. 2026-09-11 QA에서
 * 그때 인사말이 안내 없이 사라졌다. "실패가 화면에 보이지 않는 것"은 눈으로 잡기 어려운
 * 회귀라 여기서 고정한다(`frontend-structure.md` 7.4 실패 표시 규칙).
 *
 * - 프로필이 실패하면 그 자리에 안내와 `다시 시도`가 서고 나머지 섹션은 그대로 보인다
 * - 서버에 닿지 못한 것과 서버가 처리하지 못한 것을 보조 문구가 가른다
 * - `다시 시도`는 프로필을 다시 부르고 성공하면 인사말이 돌아온다
 *
 * `api/*`를 모두 가로챈다. 프로필 · 파티 · OTT 기준정보가 실제 호출이라(업무 -364 · -393 ·
 * -379) 가로채지 않으면 네트워크를 두드린다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import HomePage from './index';
import { renderWithRouter } from '../../../test/render';
import { ApiError, NETWORK_ERROR } from '../../../api/apiError';
import {
  NETWORK_FAILURE_DESCRIPTION,
  SERVER_FAILURE_DESCRIPTION,
} from '../../../components/StatusView/failure';
import { getMyProfile } from '../../../api/users';
import { getOtts } from '../../../api/otts';
import { getRecommendations } from '../../../api/recommendations';
import { getAvailableNowMovies } from '../../../api/home';
import { getMyTaste } from '../../../api/taste';
import { getMyParties } from '../../../api/parties';
import { mockMyProfile } from '../../../mocks/user';
import { mockTasteAnalysis } from '../../../mocks/taste';

vi.mock('../../../api/users', () => ({ getMyProfile: vi.fn() }));
vi.mock('../../../api/otts', () => ({ getOtts: vi.fn() }));
vi.mock('../../../api/recommendations', () => ({ getRecommendations: vi.fn() }));
vi.mock('../../../api/home', () => ({ getAvailableNowMovies: vi.fn() }));
vi.mock('../../../api/taste', () => ({ getMyTaste: vi.fn() }));
vi.mock('../../../api/parties', () => ({ getMyParties: vi.fn() }));
vi.mock('../../../api/reports', () => ({ getLatestReadyReport: vi.fn() }));

const GREETING = '김난다 님, 오늘은 어떤 영화를 볼까요?';
const PROFILE_FAILED = '프로필을 불러오지 못했어요';

/** 요청이 서버에 닿지 못했다. 공통 클라이언트가 `fetch` 예외를 이렇게 바꾼다 */
const unreachable = () => new ApiError(0, NETWORK_ERROR, '네트워크에 연결할 수 없습니다.');

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

describe('2.1 홈 실패 표시', () => {
  it('프로필을 받으면 인사말을 보여준다', async () => {
    renderWithRouter(<HomePage />);

    expect(await screen.findByText(GREETING)).toBeInTheDocument();
    expect(screen.queryByText(PROFILE_FAILED)).toBeNull();
  });

  it('서버에 닿지 못하면 인사말 자리에 연결을 확인하라는 안내와 다시 시도가 선다', async () => {
    vi.mocked(getMyProfile).mockRejectedValue(unreachable());
    renderWithRouter(<HomePage />);

    expect(await screen.findByText(PROFILE_FAILED)).toBeInTheDocument();
    expect(screen.getByText(NETWORK_FAILURE_DESCRIPTION)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.queryByText(GREETING)).toBeNull();
  });

  it('서버가 처리하지 못하면 서버 문구 대신 잠시 후 다시 시도하라고 안내한다', async () => {
    vi.mocked(getMyProfile).mockRejectedValue(
      new ApiError(500, 'INTERNAL_ERROR', '서버가 준 문구'),
    );
    renderWithRouter(<HomePage />);

    expect(await screen.findByText(PROFILE_FAILED)).toBeInTheDocument();
    expect(screen.getByText(SERVER_FAILURE_DESCRIPTION)).toBeInTheDocument();
    expect(screen.queryByText('서버가 준 문구')).toBeNull();
  });

  it('프로필이 실패해도 다른 섹션은 그대로 보인다', async () => {
    vi.mocked(getMyProfile).mockRejectedValue(unreachable());
    renderWithRouter(<HomePage />);

    expect(await screen.findByText(PROFILE_FAILED)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: '내 취향 요약' })).toBeInTheDocument();
    expect(await screen.findByText('참여 중인 파티가 없어요')).toBeInTheDocument();
  });

  it('다시 시도가 프로필을 다시 받으면 인사말이 돌아온다', async () => {
    vi.mocked(getMyProfile).mockRejectedValueOnce(unreachable()).mockResolvedValue(mockMyProfile);
    renderWithRouter(<HomePage />);

    fireEvent.click(await screen.findByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText(GREETING)).toBeInTheDocument();
    expect(screen.queryByText(PROFILE_FAILED)).toBeNull();
    expect(getMyProfile).toHaveBeenCalledTimes(2);
  });
});
