/*
 * 7.2-C 사용자 취향 비교 (업무 S15P21E106-305)
 *
 * `같이 볼 영화 추천받기`는 이동표에 목적지가 없던 화살표다. 5.2 인원 설정으로
 * 확정했다(2026-09-11, screen-spec-my.md 2장 11번).
 *
 * `getTasteCompare`는 목업이지만 그 안에서 `getMyProfile`(실제 호출)을 부른다.
 * 그래서 `api/users`를 가로챈다.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import TasteComparePage from './index';
import { renderWithRouter } from '../../../test/render';
import { getTasteCompare } from '../../../api/users';
import { mockTasteCompare } from '../../../mocks/publicProfiles';

const USER_ID = 2048;

vi.mock('../../../api/users', () => ({
  getTasteCompare: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(getTasteCompare).mockReset().mockResolvedValue(mockTasteCompare[USER_ID]);
});

async function renderCompare() {
  const result = renderWithRouter(<TasteComparePage />, {
    route: `/users/${USER_ID}/compare`,
    path: '/users/:userId/compare',
  });
  await screen.findByText('김난다 × 무비버디');

  return result;
}

describe('7.2-C 사용자 취향 비교', () => {
  it('같이 볼 영화 추천받기가 파티 인원 설정으로 간다', async () => {
    await renderCompare();

    fireEvent.click(screen.getByRole('button', { name: '같이 볼 영화 추천받기' }));

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/party/new'));
  });

  it('전체 일치율과 항목별 비교를 보조기술이 읽을 수 있다', async () => {
    /*
     * 링과 막대는 그림이라 눈으로만 읽힌다. 값이 접근 가능한 이름에 실리지 않으면
     * 화면 낭독기에게 이 화면은 빈 화면이다.
     */
    await renderCompare();

    expect(screen.getByRole('img', { name: '취향 일치율 76퍼센트' })).toBeInTheDocument();

    const genre = screen.getByRole('progressbar', { name: '장르' });
    expect(genre).toHaveAttribute('aria-valuenow', '82');

    expect(screen.getAllByRole('progressbar')).toHaveLength(4);
  });

  it('승패로 읽히는 표현을 쓰지 않는다', async () => {
    // 레퍼런스 지시 4.5 — `누가 더`가 아니라 `무엇을 함께`다
    await renderCompare();

    expect(screen.getByText('두 사람이 이만큼 같은 걸 좋아해요')).toBeInTheDocument();
    expect(screen.getByText('함께 좋아하는 맛')).toBeInTheDocument();
  });
});
