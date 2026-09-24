/*
 * 5.10 파티 추천 이력 — API-083 연동 (업무 S15P21E106-826).
 *
 * 화면이 지키는 것을 본다.
 * - 세트 최신순으로 그리고 카드는 영화 상세로 간다
 * - 한 세트를 여러 번에 걸쳐 받았으면(다시 받기) 묶음마다 노출 시각을 적고, 한 묶음뿐인
 *   세트에는 적지 않는다
 * - `더 보기`가 커서로 다음 장을 이어 붙이고 같은 세트를 두 번 그리지 않는다
 * - 빈 이력 · 404 · 그 밖의 실패가 서로 다른 문구와 다음 행동을 준다
 * - 끝난 파티도 활성 파티도 같은 화면에서 본다
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import PartyHistoryPage from './index';
import { ApiError } from '../../../api/apiError';
import { getParty, getPartyRecommendationHistory } from '../../../api/parties';
import { renderWithRouter } from '../../../test/render';
import { historyItem, historyPage, historySet, party, TEST_PARTY_ID } from '../../../test/party';

vi.mock('../../../api/parties', async () => {
  const actual =
    await vi.importActual<typeof import('../../../api/parties')>('../../../api/parties');

  return {
    ...actual,
    getParty: vi.fn(),
    getPartyRecommendationHistory: vi.fn(),
  };
});

beforeEach(() => {
  vi.mocked(getParty).mockReset().mockResolvedValue(party());
  vi.mocked(getPartyRecommendationHistory).mockReset().mockResolvedValue(historyPage());
});

function renderPage() {
  return renderWithRouter(<PartyHistoryPage />, {
    route: `/parties/${TEST_PARTY_ID}/recommendations/history`,
    path: '/parties/:partyId/recommendations/history',
  });
}

const location = () => screen.getByTestId('location').textContent;

describe('5.10 파티 추천 이력', () => {
  it('이 파티의 이력을 받아 세트별로 그리고, 카드는 영화 상세로 간다', async () => {
    renderPage();

    expect(await screen.findByText('2026. 9. 20')).toBeInTheDocument();
    expect(getPartyRecommendationHistory).toHaveBeenCalledWith(TEST_PARTY_ID);
    expect(screen.getByText('2편')).toBeInTheDocument();

    const card = screen.getByRole('link', { name: /펄프 픽션/ });

    expect(card).toHaveAttribute('href', '/movies/552');
    fireEvent.click(card);
    expect(location()).toBe('/movies/552');
  });

  it('파티 이름을 머리에 적는다. 끝난 파티는 기본 목록에 없어 종료분까지 찾는다', async () => {
    renderPage();

    expect(await screen.findByText('금요 심야 상영회')).toBeInTheDocument();
    expect(getParty).toHaveBeenCalledWith(TEST_PARTY_ID, { includeClosed: true });
  });

  it('개봉 연도와 장르를 제목 아래에 적고, 없는 값은 자리를 차지하지 않는다', async () => {
    vi.mocked(getPartyRecommendationHistory).mockResolvedValue(
      historyPage({
        items: [
          historySet({
            items: [
              historyItem(1, '파이트 클럽', '2026-09-20T10:01:00Z'),
              historyItem(2, '제목만 있는 영화', '2026-09-20T10:01:00Z', {
                releaseYear: null,
                genres: [],
              }),
            ],
          }),
        ],
      }),
    );
    renderPage();

    expect(await screen.findByText('1999 · 드라마 · 스릴러')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /제목만 있는 영화/ })).toBeInTheDocument();
  });
});

/*
 * 다시 받기(API-058 `refresh`)는 같은 세트 안에 노출 시각이 다른 묶음을 만든다.
 * 계약이 `클라이언트가 묶음으로 나눠 보일 수 있다`고 적어 둔 부분이다.
 */
describe('세트와 노출 묶음', () => {
  it('묶음이 둘 이상인 세트는 묶음마다 노출 시각을 적는다', async () => {
    vi.mocked(getPartyRecommendationHistory).mockResolvedValue(
      historyPage({
        items: [
          historySet({
            items: [
              historyItem(1, '파이트 클럽', '2026-09-20T01:01:00Z'),
              historyItem(2, '펄프 픽션', '2026-09-20T01:01:00Z'),
              historyItem(3, '포레스트 검프', '2026-09-20T04:30:00Z'),
            ],
          }),
        ],
      }),
    );
    renderPage();

    // 노출 시각은 보는 사람의 시간대로 적는다. 두 묶음이 서로 다른 줄이라는 것만 본다
    expect(await screen.findByText('3편')).toBeInTheDocument();

    const times = screen.getAllByText(/^(오전|오후) \d{1,2}:\d{2}$/);

    expect(times).toHaveLength(2);
    expect(times[0].textContent).not.toBe(times[1].textContent);
  });

  it('한 묶음뿐인 세트에는 시각 줄을 붙이지 않는다', async () => {
    renderPage();

    expect(await screen.findByText('2026. 9. 20')).toBeInTheDocument();
    expect(screen.queryByText(/^(오전|오후) \d{1,2}:\d{2}$/)).toBeNull();
  });
});

describe('다음 장 (커서)', () => {
  it('더 보기가 커서로 다음 장을 이어 붙이고, 끝나면 사라진다', async () => {
    vi.mocked(getPartyRecommendationHistory)
      .mockResolvedValueOnce(historyPage({ nextCursor: 'cursor-2', hasNext: true }))
      .mockResolvedValueOnce(
        historyPage({
          items: [
            historySet({
              recommendationSetId: 7099,
              generatedAt: '2026-09-18T10:00:00Z',
              items: [historyItem(9, '올드보이', '2026-09-18T10:05:00Z')],
            }),
          ],
        }),
      );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }));

    expect(await screen.findByText('2026. 9. 18')).toBeInTheDocument();
    expect(getPartyRecommendationHistory).toHaveBeenLastCalledWith(TEST_PARTY_ID, {
      cursor: 'cursor-2',
    });
    // 먼저 받은 세트는 그대로 남고 같은 세트가 두 번 그려지지 않는다
    expect(screen.getAllByText('2026. 9. 20')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
  });

  it('다음 장이 없으면 더 보기를 두지 않는다', async () => {
    renderPage();

    expect(await screen.findByText('2026. 9. 20')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
  });

  it('다음 장이 실패해도 받은 세트는 남고 다시 불러올 수 있다', async () => {
    vi.mocked(getPartyRecommendationHistory)
      .mockResolvedValueOnce(historyPage({ nextCursor: 'cursor-2', hasNext: true }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(
        historyPage({
          items: [
            historySet({
              recommendationSetId: 7099,
              generatedAt: '2026-09-18T10:00:00Z',
              items: [historyItem(9, '올드보이', '2026-09-18T10:05:00Z')],
            }),
          ],
        }),
      );
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }));

    const retry = await screen.findByRole('button', { name: '다시 불러오기' });

    expect(screen.getByText('2026. 9. 20')).toBeInTheDocument();
    fireEvent.click(retry);
    expect(await screen.findByText('2026. 9. 18')).toBeInTheDocument();
  });
});

describe('빈 이력과 실패', () => {
  it('이력이 비었으면 파티로 보내 추천을 받게 한다', async () => {
    vi.mocked(getPartyRecommendationHistory).mockResolvedValue(historyPage({ items: [] }));
    renderPage();

    expect(await screen.findByText('아직 추천받은 영화가 없어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '파티로 가기' }));
    expect(location()).toBe(`/parties/${TEST_PARTY_ID}/popcorn`);
  });

  it('끝난 파티의 빈 이력은 내 파티로 돌려보낸다', async () => {
    vi.mocked(getParty).mockResolvedValue(party({ status: 'CLOSED' }));
    vi.mocked(getPartyRecommendationHistory).mockResolvedValue(historyPage({ items: [] }));
    renderPage();

    expect(await screen.findByText(/추천받기 전에 끝났어요/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '내 파티로 돌아가기' }));
    expect(location()).toBe('/party');
  });

  it('파티원이 아니면(404) 빈 이력과 다른 문구로 알리고 내 파티로 돌려보낸다', async () => {
    vi.mocked(getPartyRecommendationHistory).mockRejectedValue(
      new ApiError(404, 'RESOURCE_NOT_FOUND', '파티를 찾을 수 없습니다.'),
    );
    renderPage();

    expect(await screen.findByText('추천 이력을 볼 수 없어요')).toBeInTheDocument();
    expect(screen.queryByText('아직 추천받은 영화가 없어요')).toBeNull();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '내 파티로 돌아가기' }));
    expect(location()).toBe('/party');
  });

  it('그 밖의 실패는 원인을 적고 다시 시도하게 한다', async () => {
    vi.mocked(getPartyRecommendationHistory)
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(historyPage());
    renderPage();

    expect(await screen.findByText('추천 이력을 불러오지 못했어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('2026. 9. 20')).toBeInTheDocument();
  });

  it('파티 이름을 못 받아도 이력은 그대로 보여준다', async () => {
    vi.mocked(getParty).mockRejectedValue(new TypeError('Failed to fetch'));
    renderPage();

    expect(await screen.findByText('2026. 9. 20')).toBeInTheDocument();
    expect(screen.getByText('지난 추천')).toBeInTheDocument();
  });
});
