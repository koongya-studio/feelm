/*
 * 6.1 구독 OTT 관리 (업무 S15P21E106-303 · S15P21E106-379)
 *
 * 와이어프레임이 화면 아래에 **`1.2 구독 OTT 초기 설정과 항상 일치해요`**라고 못 박아
 * 두었다. 두 화면이 같은 계약(API-026 조회 · API-027 전체 교체)을 쓰기 때문에 일치하는
 * 것인데, 어느 한쪽이 자기 상태를 따로 들기 시작하면 조용히 어긋난다. 여기서 고정한다.
 *
 * 켜고 끌 때 **바뀐 목록 전체**를 보내는 것도 함께 본다. 계약이 부분 갱신이 아니라
 * 전체 교체(멱등)라 하나만 보내면 나머지가 지워진다.
 *
 * 연동(-379) 뒤에는 구독 목록이 **서버가 아는 값**이다. 목업이 돌려주던 기준값
 * `[1, 2, 3, 4]`가 사라졌으므로 한 번도 설정하지 않은 사용자가 무엇을 보는지도 고정한다.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import OttManagePage from './index';
import { renderWithRouter } from '../../../test/render';
import { mockOtts } from '../../../mocks/otts';

vi.mock('../../../api/otts', () => ({
  getOtts: vi.fn(),
  getMyOttIds: vi.fn(),
  setMyOttIds: vi.fn(),
}));

const { getOtts, getMyOttIds, setMyOttIds } = await import('../../../api/otts');

/** 기준정보는 API-025가 주는 확정 5종이다. 화면은 이 식별자를 그대로 쓴다 */
const NETFLIX = mockOtts[0];
const WATCHA = mockOtts[2];

beforeEach(() => {
  vi.mocked(getOtts).mockReset().mockResolvedValue(mockOtts);
  // 넷플릭스와 왓챠를 구독 중인 사용자
  vi.mocked(getMyOttIds).mockReset().mockResolvedValue([NETFLIX.ottId, WATCHA.ottId]);
  vi.mocked(setMyOttIds)
    .mockReset()
    .mockImplementation((ottIds: number[]) => Promise.resolve(ottIds));
});

function toggleOf(name: string) {
  return screen.getByRole('switch', { name: `${name} 구독` });
}

describe('6.1 구독 OTT 관리', () => {
  it('구독 중과 미구독을 나눠 보여준다', async () => {
    renderWithRouter(<OttManagePage />, { route: '/otts' });

    await waitFor(() => expect(screen.getByText('구독 중 2')).toBeInTheDocument());
    expect(screen.getByText('미구독')).toBeInTheDocument();

    expect(toggleOf('넷플릭스')).toHaveAttribute('aria-checked', 'true');
    expect(toggleOf('왓챠')).toHaveAttribute('aria-checked', 'true');
    expect(toggleOf('티빙')).toHaveAttribute('aria-checked', 'false');
  });

  /*
   * 목업 시절에는 설정한 적이 없어도 네 개가 켜져 있었다. 계약은 빈 배열을 주므로
   * (API-026 처리 규칙) 구독 중 묶음 자체가 없고 다섯 개가 모두 미구독이다.
   */
  it('구독을 설정한 적이 없으면 모두 미구독이다', async () => {
    vi.mocked(getMyOttIds).mockResolvedValue([]);

    renderWithRouter(<OttManagePage />, { route: '/otts' });

    expect(await screen.findByText('미구독')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /^구독 중/ })).not.toBeInTheDocument();

    for (const ott of mockOtts) {
      expect(toggleOf(ott.name)).toHaveAttribute('aria-checked', 'false');
    }
  });

  it('끄면 그 OTT만 빠진 목록 전체를 보낸다', async () => {
    renderWithRouter(<OttManagePage />, { route: '/otts' });

    await waitFor(() => expect(screen.getByText('구독 중 2')).toBeInTheDocument());

    fireEvent.click(toggleOf('넷플릭스'));

    await waitFor(() => expect(setMyOttIds).toHaveBeenCalledWith([WATCHA.ottId]));
    expect(screen.getByText('구독 중 1')).toBeInTheDocument();
  });

  it('켜면 목록에 더해진다', async () => {
    renderWithRouter(<OttManagePage />, { route: '/otts' });

    await waitFor(() => expect(screen.getByText('구독 중 2')).toBeInTheDocument());

    fireEvent.click(toggleOf('티빙'));

    await waitFor(() =>
      expect(setMyOttIds).toHaveBeenCalledWith([NETFLIX.ottId, WATCHA.ottId, mockOtts[4].ottId]),
    );
    expect(screen.getByText('구독 중 3')).toBeInTheDocument();
  });

  it('처음 불러오지 못하면 안내와 다시 시도를 보여주고, 누르면 다시 부른다', async () => {
    // 첫 조회만 실패시킨다. 다시 시도가 실제로 다시 부르는지 보려면 두 번째는 성공해야 한다
    vi.mocked(getMyOttIds).mockRejectedValueOnce(new Error('실패'));

    renderWithRouter(<OttManagePage />, { route: '/otts' });

    expect(await screen.findByText('OTT 목록을 불러오지 못했어요')).toBeInTheDocument();
    expect(screen.queryByRole('switch', { name: '넷플릭스 구독' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    await waitFor(() => expect(screen.getByText('구독 중 2')).toBeInTheDocument());
    expect(screen.queryByText('OTT 목록을 불러오지 못했어요')).not.toBeInTheDocument();
  });

  it('겹친 요청 중 하나가 실패해도 그 사이 성공한 토글을 지우지 않는다', async () => {
    // 첫 요청(넷플릭스 끄기)만 실패시킨다. 두 번째(티빙 켜기)는 성공한다
    vi.mocked(setMyOttIds).mockRejectedValueOnce(new Error('실패'));

    renderWithRouter(<OttManagePage />, { route: '/otts' });
    await waitFor(() => expect(screen.getByText('구독 중 2')).toBeInTheDocument());

    fireEvent.click(toggleOf('넷플릭스'));
    fireEvent.click(toggleOf('티빙'));

    // 실패한 넷플릭스만 켜진 상태로 돌아오고, 성공한 티빙은 켜진 채 남아야 한다
    await waitFor(() => expect(toggleOf('넷플릭스')).toHaveAttribute('aria-checked', 'true'));
    expect(toggleOf('티빙')).toHaveAttribute('aria-checked', 'true');
  });

  it('저장에 실패하면 화면을 되돌린다', async () => {
    vi.mocked(setMyOttIds).mockRejectedValueOnce(new Error('실패'));

    renderWithRouter(<OttManagePage />, { route: '/otts' });

    await waitFor(() => expect(screen.getByText('구독 중 2')).toBeInTheDocument());

    fireEvent.click(toggleOf('넷플릭스'));

    await waitFor(() => expect(toggleOf('넷플릭스')).toHaveAttribute('aria-checked', 'true'));
  });
});
