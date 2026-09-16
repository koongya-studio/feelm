/*
 * 7.8-D 차단 사용자 관리 — 실제 API로 바뀌며 생긴 두 가지 (업무 S15P21E106-394).
 *
 * 이동 경로와 해제의 정상 흐름은 `SettingsPage/SettingsFlow.test.tsx`가 밟는다. 여기서는
 * 목록이 서버에서 오기 때문에 생긴 것만 본다.
 * - 계약 상한(50)을 넘겨 더 남아 있으면 `N명 차단 중`을 감춘다. 받은 만큼만 세면 틀린 수다
 * - 해제가 실패하면 목록에서 지우지 않는다. 지워 놓으면 서버에 남은 차단이 화면에서 사라진다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import BlockedUsersPage from './index';
import { resetSessionForTest, startSession } from '../../../api/session';
import { renderWithRouter } from '../../../test/render';
import type { BlockedUser } from '../../../types/user';

const BLOCKED: BlockedUser[] = [
  { userId: 5201, nickname: '스포일러왕', blockedAt: '2026-08-30T12:00:00Z' },
];

function json(status: number, body?: unknown): Response {
  return body === undefined
    ? new Response(null, { status })
    : new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      });
}

/** 목록은 `hasNext`대로, 해제는 주어진 응답으로 답한다 */
function stubServer({ hasNext = false, unblock = json(204) } = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        url.startsWith('/api/v1/users/me/blocks')
          ? json(200, { items: BLOCKED, nextCursor: hasNext ? 'next' : null, hasNext })
          : unblock,
      ),
    ),
  );
}

beforeEach(() => {
  resetSessionForTest();
  startSession('token');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('7.8-D 차단 사용자 관리', () => {
  it('첫 페이지가 전부면 인원을 센다', async () => {
    stubServer();
    renderWithRouter(<BlockedUsersPage />);

    expect(await screen.findByText('1명 차단 중')).toBeInTheDocument();
  });

  it('더 남아 있으면 인원을 적지 않는다', async () => {
    stubServer({ hasNext: true });
    renderWithRouter(<BlockedUsersPage />);

    expect(await screen.findByText('스포일러왕')).toBeInTheDocument();
    expect(screen.queryByText('1명 차단 중')).toBeNull();
  });

  it('해제하지 못하면 목록에 그대로 둔다', async () => {
    stubServer({ unblock: json(500, { code: 'INTERNAL_ERROR', message: '오류' }) });
    renderWithRouter(<BlockedUsersPage />);

    fireEvent.click(await screen.findByRole('button', { name: '스포일러왕 차단 해제' }));

    expect(
      await screen.findByText('차단을 해제하지 못했어요. 잠시 후 다시 시도해 주세요'),
    ).toBeInTheDocument();
    expect(screen.getByText('스포일러왕')).toBeInTheDocument();
  });
});
