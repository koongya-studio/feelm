/*
 * 7.1 MY 대시보드 — 이동 경로와 피드 (업무 S15P21E106-305 · -459)
 *
 * 메뉴는 눌러서 어디로 가는지가 전부다. 링크 주소가 어긋나도 화면은 멀쩡해 보이므로
 * 목적지를 고정한다(screen-spec-my.md 2장).
 *
 * 사용자 시안(2026-09-16)으로 **평가 · 안 남긴 영화 · 추천 기록이 화면 안의 탭이 됐다.**
 * 눌러도 다른 화면으로 가지 않고 아래 피드만 바뀐다. 7.5 · 7.6 · 7.7 화면은 지웠다
 * (업무 -495). 그래서 이 파일이 잡을 것이 둘로 늘었다.
 *
 * - 남은 이동(`설정` · `4컷 리포트` · `편집`)의 목적지. `OTT 관리`는 7.8 설정 안으로
 *   옮겨 이 화면에 없다(`SettingsFlow.test.tsx`가 잡는다)
 * - 탭을 눌렀을 때 **그 탭의 데이터**가 그려지는지. 평가는 API-039, 안 남긴 영화는
 *   API-034, 추천은 API-048이다. 취향 필름(API-042)을 이 셋인 것처럼 쓰면 여기서 걸린다
 * - 추천 기록의 **`더 보기`가 직전 응답의 커서로 다음 장을 이어 붙이는지**(업무 -478).
 *   첫 장만 그려도 화면은 멀쩡해 보이므로 눈으로는 빠진 것을 알 수 없다
 *
 * `api/users` · `api/watch` · `api/recommendations` · `api/otts`를 가로챈다. API-014와
 * OTT 기준정보가 실제 호출이라(업무 -364 · -379) 가로채지 않으면 네트워크를 두드린다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import MyDashboardPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getMyProfile } from '../../../api/users';
import { getMyRatings, getWatchRecords } from '../../../api/watch';
import { getOtts } from '../../../api/otts';
import { getRecommendationHistory } from '../../../api/recommendations';
import { mockMyProfile } from '../../../mocks/user';
import type { MyRatedItem, MyRatingList } from '../../../types/watch';
import type { WatchRecordListItem } from '../../../types/watch';
import type { RecommendationHistory } from '../../../types/recommendation';

vi.mock('../../../api/users', () => ({
  getMyProfile: vi.fn(),
}));

vi.mock('../../../api/watch', () => ({
  getMyRatings: vi.fn(),
  getWatchRecords: vi.fn(),
}));

vi.mock('../../../api/taste', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/taste')>()),
  getPopcornBucket: vi.fn().mockResolvedValue({ totalCount: 0, flavors: [] }),
}));

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
}));

vi.mock('../../../api/recommendations', () => ({
  getRecommendationHistory: vi.fn(),
}));

/** API-039 평가 한 건. `평가한 영화` 탭의 원천이다(업무 -611) */
function rated(
  ratingId: number,
  movieId: number,
  title: string,
  score: number,
  frameId: number | null = ratingId + 400,
): MyRatedItem {
  const createdAt = '2026-09-05T09:00:00Z';

  return {
    itemType: 'RATED',
    ratingId,
    movie: { movieId, title, posterUrl: null },
    score,
    comment: null,
    visibility: null,
    spoiler: false,
    frameId,
    createdAt,
    activityAt: createdAt,
  };
}

const ratings: MyRatedItem[] = [
  rated(91, 401, '평가한 영화 A', 4.5),
  rated(92, 402, '평가한 영화 B', 3),
];

function ratingPage(items: MyRatedItem[], nextCursor: string | null = null): MyRatingList {
  return { items, nextCursor, hasNext: nextCursor !== null };
}

/** 본 기록은 있는데 평가가 없는 영화. API-034 `WATCHED_UNRATED` */
const unrated: WatchRecordListItem[] = [
  {
    watchRecordId: 701,
    movie: { movieId: 501, title: '안 남긴 영화 A', posterUrl: null },
    status: 'WATCHED_UNRATED',
    ott: { ottId: 1, code: 'NETFLIX' },
    clickedAt: '2026-08-26T09:00:00Z',
    confirmDueAt: '2026-08-27T09:00:00Z',
    expiresAt: '2026-09-26T09:00:00Z',
    watchedAt: '2026-08-26T09:00:00Z',
    ratingId: null,
  },
];

const history: RecommendationHistory = {
  items: [
    {
      recommendationSetId: 1,
      generatedAt: '2026-09-07T09:00:00Z',
      items: [
        {
          recommendationItemId: 11,
          type: 'TASTE',
          movie: { movieId: 601, title: '추천받은 영화 A', posterUrl: null },
          reason: { summary: '취향과 가까워요' },
          dismissed: false,
          watchStatus: null,
        },
        {
          recommendationItemId: 12,
          type: 'DISCOVERY',
          movie: { movieId: 602, title: '추천받은 영화 B', posterUrl: null },
          reason: { summary: '새로운 맛이에요' },
          dismissed: false,
          watchStatus: null,
        },
      ],
    },
  ],
  nextCursor: null,
  hasNext: false,
};

beforeEach(() => {
  vi.mocked(getMyProfile).mockReset().mockResolvedValue(mockMyProfile);
  vi.mocked(getMyRatings).mockReset().mockResolvedValue(ratingPage(ratings));
  vi.mocked(getRecommendationHistory).mockReset().mockResolvedValue(history);
  vi.mocked(getWatchRecords)
    .mockReset()
    .mockResolvedValue({ items: unrated, nextCursor: null, hasNext: false });
  vi.mocked(getOtts)
    .mockReset()
    .mockResolvedValue([{ ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null }]);
});

async function renderDashboard() {
  const result = renderWithRouter(<MyDashboardPage />, { route: '/my' });
  await screen.findByRole('link', { name: '프로필 편집' });

  return result;
}

const feed = () => screen.getByRole('tabpanel');

describe('7.1 MY 대시보드', () => {
  it('남은 이동 셋이 각자의 화면으로 간다', async () => {
    await renderDashboard();

    const destinations = Object.fromEntries(
      ['설정', '4컷 리포트', '프로필 편집'].map((name) => [
        name,
        screen.getByRole('link', { name }).getAttribute('href'),
      ]),
    );

    expect(destinations).toEqual({
      설정: '/my/settings',
      // 취향 리포트 목록이다. 지난 반기 리포트를 찾아보러 가는 유일한 길이다(2장 13번)
      '4컷 리포트': '/my/reports',
      // 이동 경로 2 · 3번 사슬의 출발점이다
      '프로필 편집': '/my/profile/edit',
    });

    // 공유는 이동이 아니라 기기의 공유 메뉴를 여는 버튼이다(계약에 공유 API가 없다)
    expect(screen.getByRole('button', { name: '프로필 공유' })).toBeInTheDocument();

    // OTT 관리는 설정 안으로 옮겼다(사용자 지시 2026-09-16)
    expect(screen.queryByRole('link', { name: 'OTT 관리' })).toBeNull();
  });

  it('처음에는 평가한 영화 탭이고, 평가 포스터가 별점과 함께 깔린다', async () => {
    await renderDashboard();

    expect(screen.getByRole('tab', { name: '평가한 영화' })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    const cells = await within(feed()).findAllByRole('link');

    expect(cells).toHaveLength(2);
    // 7.6과 같은 곳으로 간다 — 2.5 평가 수정이다
    expect(cells[0]).toHaveAttribute('href', '/movies/401/rate');
    expect(cells[0]).toHaveTextContent('평가한 영화 A');
    expect(cells[0]).toHaveTextContent('4.5');
    expect(cells[1]).toHaveTextContent('3.0');
  });

  it('안 남긴 영화 탭은 감상 기록을 깔고 2.5 평가하기로 간다', async () => {
    /*
     * 7.7이 하던 일이다(업무 -495에서 화면을 지우고 탭으로 옮겼다). 날짜와 OTT는
     * API-034 기록과 API-025 기준정보가 주는 값이다.
     */
    await renderDashboard();

    fireEvent.click(screen.getByRole('tab', { name: '안 남긴 영화' }));

    const cells = within(feed()).getAllByRole('link');

    expect(cells).toHaveLength(1);
    expect(cells[0]).toHaveAttribute('href', '/movies/501/rate');
    expect(cells[0]).toHaveTextContent('안 남긴 영화 A');
    expect(cells[0]).toHaveTextContent('넷플릭스');
    expect(cells[0]).toHaveTextContent('8. 26');
  });

  it('추천 기록 탭을 누르면 화면 안에서 피드가 바뀐다', async () => {
    await renderDashboard();
    await within(feed()).findAllByRole('link');

    fireEvent.click(screen.getByRole('tab', { name: '추천 기록' }));

    const cells = within(feed()).getAllByRole('link');

    expect(cells).toHaveLength(2);
    // 7.5와 같은 곳으로 간다 — 2.4 영화 상세다
    expect(cells[0]).toHaveAttribute('href', '/movies/601');
    expect(cells[0]).toHaveTextContent('추천받은 영화 A');
    // 출처와 날짜는 API-048이 주는 값만 쓴다
    expect(cells[0]).toHaveTextContent('취향 맞춤');
    expect(cells[1]).toHaveTextContent('새로운 맛 발견');

    // 평가 포스터는 사라졌다. 두 피드가 섞이지 않는다
    expect(within(feed()).queryByText('평가한 영화 A')).toBeNull();
  });

  it('추천 기록의 `더 보기`가 직전 커서로 다음 장을 이어 붙인다', async () => {
    const second: RecommendationHistory = {
      items: [
        {
          recommendationSetId: 2,
          generatedAt: '2026-08-30T09:00:00Z',
          items: [
            {
              recommendationItemId: 21,
              type: 'TASTE',
              movie: { movieId: 603, title: '지난 달 추천', posterUrl: null },
              reason: { summary: '지난 세트' },
              dismissed: false,
              watchStatus: null,
            },
          ],
        },
      ],
      nextCursor: null,
      hasNext: false,
    };

    vi.mocked(getRecommendationHistory).mockResolvedValue({
      ...history,
      nextCursor: 'cursor-2',
      hasNext: true,
    });

    await renderDashboard();
    fireEvent.click(screen.getByRole('tab', { name: '추천 기록' }));

    vi.mocked(getRecommendationHistory).mockResolvedValue(second);
    fireEvent.click(await within(feed()).findByRole('button', { name: '더 보기' }));

    expect(await within(feed()).findByText('지난 달 추천')).toBeInTheDocument();
    // 앞 장은 그대로 남는다
    expect(within(feed()).getByText('추천받은 영화 A')).toBeInTheDocument();
    expect(getRecommendationHistory).toHaveBeenLastCalledWith('cursor-2');
    // 마지막 장이므로 버튼이 사라진다
    expect(within(feed()).queryByRole('button', { name: '더 보기' })).toBeNull();
  });

  it('다음 장이 실패해도 보던 격자는 그대로 두고 다시 시도만 준다', async () => {
    vi.mocked(getRecommendationHistory).mockResolvedValue({
      ...history,
      nextCursor: 'cursor-2',
      hasNext: true,
    });

    await renderDashboard();
    fireEvent.click(screen.getByRole('tab', { name: '추천 기록' }));

    vi.mocked(getRecommendationHistory).mockRejectedValue(new Error('boom'));
    fireEvent.click(await within(feed()).findByRole('button', { name: '더 보기' }));

    expect(await within(feed()).findByText('지난 추천을 불러오지 못했어요.')).toBeInTheDocument();
    expect(within(feed()).getByText('추천받은 영화 A')).toBeInTheDocument();
    // 피드 전체가 실패 상태로 넘어가지 않는다
    expect(within(feed()).queryByText('목록을 불러오지 못했어요')).toBeNull();
  });

  it('다음 장이 없는 탭에는 `더 보기`가 없다. 버튼은 탭마다 따로 선다', async () => {
    /*
     * 세 탭이 같은 규칙이다(업무 -503) — 그 탭의 `hasNext`가 거짓이면 버튼이 없다. 추천 기록에
     * 다음 장이 있어도 다른 탭에 버튼이 따라 나오지 않는다.
     */
    vi.mocked(getRecommendationHistory).mockResolvedValue({
      ...history,
      nextCursor: 'cursor-2',
      hasNext: true,
    });

    await renderDashboard();

    expect(within(feed()).queryByRole('button', { name: '더 보기' })).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: '안 남긴 영화' }));
    expect(within(feed()).queryByRole('button', { name: '더 보기' })).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: '추천 기록' }));
    expect(within(feed()).getByRole('button', { name: '더 보기' })).toBeInTheDocument();
  });

  it('안 남긴 영화도 같은 조건에 커서를 실어 다음 장을 이어 붙인다', async () => {
    /*
     * 업무 -503. 그전에는 이 탭만 첫 장뿐이었다. 커서는 `status=WATCHED_UNRATED` 조건에
     * 묶이므로(API-034) 다음 장도 같은 조건으로 보낸다. OTT 이름은 첫 장의 기준정보를 쓴다.
     */
    vi.mocked(getWatchRecords).mockResolvedValueOnce({
      items: unrated,
      nextCursor: 'watch-cursor-2',
      hasNext: true,
    });

    await renderDashboard();
    fireEvent.click(screen.getByRole('tab', { name: '안 남긴 영화' }));
    await within(feed()).findByRole('link', { name: /안 남긴 영화 A/ });

    vi.mocked(getWatchRecords).mockResolvedValueOnce({
      items: [
        {
          ...unrated[0],
          watchRecordId: 702,
          movie: { movieId: 502, title: '안 남긴 영화 B', posterUrl: null },
        },
      ],
      nextCursor: null,
      hasNext: false,
    });
    fireEvent.click(within(feed()).getByRole('button', { name: '더 보기' }));

    const next = await within(feed()).findByRole('link', { name: /안 남긴 영화 B/ });
    expect(next).toHaveTextContent('넷플릭스');
    expect(within(feed()).getAllByRole('link')).toHaveLength(2);
    expect(getWatchRecords).toHaveBeenLastCalledWith({
      status: ['WATCHED_UNRATED'],
      cursor: 'watch-cursor-2',
    });
    expect(within(feed()).queryByRole('button', { name: '더 보기' })).toBeNull();
  });

  it('평가한 영화는 API-039 내 평가 목록으로 그린다', async () => {
    /*
     * 업무 -611. 그전에는 평가와 1:1인 프레임(API-042)으로 대신 그렸다(업무 -610). 필터 ·
     * 정렬을 생략해 서버 기본값(평가만 · 최신순)을 받는다.
     */
    await renderDashboard();

    expect(getMyRatings).toHaveBeenCalledTimes(1);
    expect(getMyRatings).toHaveBeenCalledWith();
    expect(
      await within(feed()).findByRole('link', { name: /평가한 영화 A.*4\.5/ }),
    ).toBeInTheDocument();
  });

  it('프레임 없는 과거 평가도 칸으로 나온다', async () => {
    // 계약이 `frameId: null`인 평가도 돌려준다고 정했다(API-039 응답 설명)
    vi.mocked(getMyRatings).mockResolvedValue(
      ratingPage([rated(93, 403, '프레임 없는 평가', 2.5, null), ...ratings]),
    );

    await renderDashboard();

    const cells = await within(feed()).findAllByRole('link');
    expect(cells).toHaveLength(3);
    expect(cells[0]).toHaveTextContent('프레임 없는 평가');
    expect(cells[0]).toHaveAttribute('href', '/movies/403/rate');
  });

  it('평가한 영화의 `더 보기`가 직전 커서로 다음 장을 이어 붙이고, 끝나면 사라진다', async () => {
    vi.mocked(getMyRatings).mockResolvedValueOnce(ratingPage(ratings, 'rating-cursor-2'));

    await renderDashboard();
    await within(feed()).findByRole('link', { name: /평가한 영화 A/ });

    vi.mocked(getMyRatings).mockResolvedValueOnce(ratingPage([rated(94, 404, '평가한 영화 C', 5)]));
    fireEvent.click(within(feed()).getByRole('button', { name: '더 보기' }));

    expect(await within(feed()).findByRole('link', { name: /평가한 영화 C/ })).toBeInTheDocument();
    // 앞 장은 그대로 남는다
    expect(within(feed()).getAllByRole('link')).toHaveLength(3);
    // 첫 장과 같은 조건(필터 · 정렬 생략)에 커서만 싣는다. 조건이 바뀐 커서는 400이다
    expect(getMyRatings).toHaveBeenLastCalledWith({ cursor: 'rating-cursor-2' });
    expect(within(feed()).queryByRole('button', { name: '더 보기' })).toBeNull();
  });

  it('평가한 영화의 다음 장이 실패해도 보던 격자는 남는다', async () => {
    vi.mocked(getMyRatings).mockResolvedValueOnce(ratingPage(ratings, 'rating-cursor-2'));

    await renderDashboard();
    await within(feed()).findByRole('link', { name: /평가한 영화 A/ });

    vi.mocked(getMyRatings).mockRejectedValueOnce(new Error('boom'));
    fireEvent.click(within(feed()).getByRole('button', { name: '더 보기' }));

    expect(await within(feed()).findByText('지난 평가를 불러오지 못했어요.')).toBeInTheDocument();
    expect(within(feed()).getAllByRole('link')).toHaveLength(2);
    expect(within(feed()).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('빈 목록과 조회 실패를 구분한다', async () => {
    vi.mocked(getMyRatings).mockResolvedValue(ratingPage([]));
    vi.mocked(getRecommendationHistory).mockRejectedValue(new Error('boom'));

    await renderDashboard();

    // 빈 목록은 가짜 포스터로 채우지 않는다
    expect(await within(feed()).findByText('아직 평가한 영화가 없어요')).toBeInTheDocument();
    expect(within(feed()).queryByRole('link')).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: '추천 기록' }));

    // 실패는 빈 목록과 다르게 말하고 다시 시도할 수 있다
    expect(within(feed()).getByText('목록을 불러오지 못했어요')).toBeInTheDocument();
    expect(within(feed()).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
  });

  it('통계 수치를 어디에도 두지 않는다', async () => {
    /*
     * API-014가 `frameCount` · `ratingCount` · `popcornCount`를 주지만 이 화면에서는
     * 쓰지 않는다(사용자 결정 2026-09-16). 목업은 셋 다 100이다.
     */
    await renderDashboard();

    for (const label of ['프레임', '팝콘', '100']) {
      expect(screen.queryByText(label)).toBeNull();
    }
  });
});
