/*
 * OTT 기준정보 Provider (업무 S15P21E106-441)
 *
 * 배지가 로고를 얻는 유일한 경로다. 여기서 중요한 것은 **실패했을 때 앱이 어떻게 되는가**다.
 * 기준정보는 화면을 그리는 데 필요한 값이 아니라 표시를 거드는 값이므로, 조회가 실패해도
 * 화면은 그대로 떠야 하고 배지는 글자로 떨어져야 한다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { OttCatalogProvider } from './OttCatalogProvider';
import OttBadge from '../components/OttBadge';
import { ApiError } from '../api/apiError';

vi.mock('../api/otts', () => ({
  getOtts: vi.fn(),
}));

const { getOtts } = await import('../api/otts');

const NETFLIX = {
  ottId: 1,
  code: 'NETFLIX',
  name: '넷플릭스',
  logoUrl: 'https://image.tmdb.org/t/p/original/netflix.png',
};

beforeEach(() => {
  vi.mocked(getOtts).mockReset().mockResolvedValue([NETFLIX]);
});

function renderWithProvider() {
  return render(
    <OttCatalogProvider>
      <OttBadge code="NETFLIX" name="넷플릭스" />
    </OttCatalogProvider>,
  );
}

describe('OTT 기준정보 Provider', () => {
  it('받은 기준정보로 배지가 로고를 찾는다', async () => {
    renderWithProvider();

    await waitFor(() =>
      // 배지는 TMDB `original`을 배지 크기(w92)로 바꿔 받는다(`OttBadge`)
      expect(screen.getByRole('img', { name: '넷플릭스' }).querySelector('img')).toHaveAttribute(
        'src',
        'https://image.tmdb.org/t/p/w92/netflix.png',
      ),
    );
  });

  /*
   * 받기 전에도 화면은 그려진다. 기준정보를 기다리며 배지 자리를 비워 두면 앱이 뜰 때마다
   * 빈 사각형이 스친다.
   */
  it('아직 받지 못했으면 글자로 그린다', () => {
    renderWithProvider();

    expect(screen.getByRole('img', { name: '넷플릭스' })).toHaveTextContent('N');
  });

  it('조회에 실패해도 화면을 막지 않고 글자로 그린다', async () => {
    vi.mocked(getOtts).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '연결할 수 없습니다.'));

    renderWithProvider();

    await waitFor(() => expect(getOtts).toHaveBeenCalled());
    expect(screen.getByRole('img', { name: '넷플릭스' })).toHaveTextContent('N');
  });
});
