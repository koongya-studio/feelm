/*
 * 5.3 파티 만들기 (업무 S15P21E106-304)
 *
 * `파티 시작하기`가 API-049를 부르고 그 응답의 `partyId`로 5.4에 간다. 새로 만든 파티가
 * 5.1 목록에 들어가는 것까지가 한 흐름인데, 화면 하나만 봐서는 목록에 들어갔는지 알 수
 * 없다.
 *
 * 이름 검증도 함께 본다. API-049가 `name` 필수 · 20자 이하를 요구하는데, 빈 이름으로
 * 보내면 400이 되므로 화면에서 막아야 한다.
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import PartySetupPage from './index';
import { renderWithRouter } from '../../../test/render';
import { getMyParties } from '../../../api/parties';
import { resetSessionStore } from '../../../mocks/sessionStore';

beforeEach(() => {
  resetSessionStore();
});

function fillName(name: string) {
  fireEvent.change(screen.getByLabelText('파티 이름'), { target: { value: name } });
}

describe('5.3 파티 만들기', () => {
  it('이름을 넣고 시작하면 파티가 만들어지고 5.4로 간다', async () => {
    renderWithRouter(<PartySetupPage />, { route: '/party/new/setup' });

    fillName('금요 심야 상영회');
    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toMatch(/^\/parties\/\d+\/analyzing$/),
    );

    const parties = await getMyParties();
    expect(parties[0].name).toBe('금요 심야 상영회');
    // 만든 사람이 파티장이다 (API-049 처리 규칙)
    expect(parties[0].members[0].role).toBe('OWNER');
  });

  it('이름이 비면 보내지 않고 알린다', async () => {
    renderWithRouter(<PartySetupPage />, { route: '/party/new/setup' });

    fireEvent.click(screen.getByRole('button', { name: '파티 시작하기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('파티 이름을 입력해 주세요.');
    expect(screen.getByTestId('location').textContent).toBe('/party/new/setup');
    expect(await getMyParties()).toHaveLength(1); // 기준 목업 하나뿐
  });

  it('정원은 계약이 정한 4명으로 보여준다', () => {
    renderWithRouter(<PartySetupPage />, { route: '/party/new/setup' });

    // 와이어프레임은 `1/6명 · 최대 6명까지`지만 API-049가 정한 정원은 4다
    expect(screen.getByText('1/4명 · 최대 4명까지')).toBeInTheDocument();
  });

  it('계약에 없는 초대 경로는 눌리지 않는다', () => {
    renderWithRouter(<PartySetupPage />, { route: '/party/new/setup' });

    expect(screen.getByRole('button', { name: '초대 링크 복사' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'QR로 초대하기' })).toBeDisabled();
  });
});
