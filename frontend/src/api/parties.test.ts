/*
 * 파티 (업무 S15P21E106-393).
 *
 * 화면에서는 드러나지 않는 것을 고정한다.
 * - **경로와 메서드.** 나가기는 `POST .../leave`, 종료는 `DELETE /parties/{id}`,
 *   내보내기는 `DELETE /parties/{id}/members/{userId}`다. 셋 다 같은 모양의 응답을 주므로
 *   잘못된 것을 불러도 화면은 멀쩡해 보인다
 * - **목록 응답의 `items` 래퍼를 벗기는 것.** 계약은 `{ items }` 하나인데 구현은 커서
 *   페이지로 내려준다
 * - **초대 단건 조회가 없다는 것.** 목록에서 고르므로 답한 초대는 `null`이다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  acceptInvitation,
  closeParty,
  createParty,
  declineInvitation,
  getClosedParties,
  getInvitation,
  getMyInvitations,
  getMyParties,
  getParty,
  getPartyPopcorn,
  getPartyRecommendationHistory,
  getPartyRecommendations,
  inviteToParty,
  kickPartyMember,
  leaveParty,
} from './parties';
import { ApiError } from './apiError';
import { resetSessionForTest, startSession } from './session';
import {
  historyPage,
  invitation,
  party,
  partyPopcorn,
  partyRecommendations,
  TEST_PARTY_ID,
} from '../test/party';

const fetchMock = vi.fn();

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function call(index = 0) {
  return fetchMock.mock.calls[index] as [string, RequestInit];
}

beforeEach(() => {
  resetSessionForTest();
  startSession('token');
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('API-050 내 파티 목록', () => {
  it('커서 페이지 응답에서 items만 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [party()], nextCursor: null, hasNext: false }));

    const parties = await getMyParties();

    expect(call()[0]).toBe('/api/v1/parties?size=50');
    expect(call()[1].method).toBe('GET');
    expect(parties).toHaveLength(1);
    expect(parties[0].owner.nickname).toBe('김난다');
  });

  it('파티가 없으면 빈 배열이다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null, hasNext: false }));

    expect(await getMyParties()).toEqual([]);
  });

  /*
   * 업무 -826. 기본 호출에는 `status`가 실리지 않아야 한다 — 서버 기본값이 활성이고,
   * 붙일 때만 붙이는 것이 계약을 읽는 사람이 기대하는 모양이다(API-058 `refresh`와 같다).
   */
  it('기본 조회에는 status를 싣지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [party()], nextCursor: null, hasNext: false }));

    await getMyParties();

    expect(call()[0]).toBe('/api/v1/parties?size=50');
  });

  it('끝난 파티는 status=CLOSED로 따로 조회한다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { items: [party({ status: 'CLOSED' })], nextCursor: null, hasNext: false }),
    );

    const parties = await getClosedParties();

    expect(call()[0]).toBe('/api/v1/parties?status=CLOSED&size=50');
    expect(parties[0].status).toBe('CLOSED');
  });
});

/*
 * 업무 -826. 5.10은 끝난 파티의 이름을 머리에 적어야 하는데 그 파티는 기본 목록에 없다.
 * **기본값은 예전 그대로다** — 5.7 · 5.8은 끝난 파티를 찾아내면 안 된다.
 */
describe('getParty 조합', () => {
  /*
   * !683 리뷰. 끝난 파티에는 상한이 없어 첫 장(50개)에 없을 수 있다. 첫 장만 보면 오래된 종료
   * 파티를 `나갔거나 내보내진 파티`로 잘못 안내했다(8.1 파티 초대 수락 알림, 90일 보관).
   */
  it('includeClosed면 종료 파티를 찾을 때까지 커서를 따라가고, 찾으면 멈춘다', async () => {
    const other = (partyId: number) => party({ partyId, status: 'CLOSED' });

    fetchMock
      .mockResolvedValueOnce(json(200, { items: [], nextCursor: null, hasNext: false }))
      .mockResolvedValueOnce(
        json(200, { items: [other(1), other(2)], nextCursor: 'c1', hasNext: true }),
      )
      .mockResolvedValueOnce(
        json(200, {
          items: [other(3), party({ status: 'CLOSED' })],
          nextCursor: 'c2',
          hasNext: true,
        }),
      );

    const found = await getParty(TEST_PARTY_ID, { includeClosed: true });

    expect(found?.status).toBe('CLOSED');
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/parties?size=50',
      '/api/v1/parties?status=CLOSED&size=50',
      '/api/v1/parties?status=CLOSED&size=50&cursor=c1',
    ]);
  });

  it('includeClosed면 마지막 장까지 봐도 없을 때만 null이다', async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, { items: [], nextCursor: null, hasNext: false }))
      .mockResolvedValueOnce(
        json(200, {
          items: [party({ partyId: 1, status: 'CLOSED' })],
          nextCursor: 'c1',
          hasNext: true,
        }),
      )
      .mockResolvedValueOnce(
        json(200, {
          items: [party({ partyId: 2, status: 'CLOSED' })],
          nextCursor: null,
          hasNext: false,
        }),
      );

    expect(await getParty(TEST_PARTY_ID, { includeClosed: true })).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('같은 커서가 다시 오면 끝없이 부르지 않고 멈춘다', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { items: [], nextCursor: null, hasNext: false }));
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        json(200, {
          items: [party({ partyId: 1, status: 'CLOSED' })],
          nextCursor: 'same',
          hasNext: true,
        }),
      ),
    );

    expect(await getParty(TEST_PARTY_ID, { includeClosed: true })).toBeNull();
    // 활성 1 + 종료 첫 장 1 + 같은 커서로 한 번 더 1
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('기본은 활성 목록만 본다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null, hasNext: false }));

    expect(await getParty(TEST_PARTY_ID)).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('includeClosed면 활성에서 못 찾았을 때 종료 파티도 본다', async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, { items: [], nextCursor: null, hasNext: false }))
      .mockResolvedValueOnce(
        json(200, { items: [party({ status: 'CLOSED' })], nextCursor: null, hasNext: false }),
      );

    const found = await getParty(TEST_PARTY_ID, { includeClosed: true });

    expect(found?.status).toBe('CLOSED');
    expect(call(1)[0]).toBe('/api/v1/parties?status=CLOSED&size=50');
  });

  it('활성에서 찾으면 종료 파티를 부르지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [party()], nextCursor: null, hasNext: false }));

    const found = await getParty(TEST_PARTY_ID, { includeClosed: true });

    expect(found?.status).toBe('ACTIVE');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('API-049 파티 생성', () => {
  it('이름과 5.2에서 고른 정원을 보낸다', async () => {
    fetchMock.mockResolvedValue(json(201, party()));

    await createParty('  금요 심야 상영회  ', 2);

    const [url, init] = call();
    expect(url).toBe('/api/v1/parties');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ name: '금요 심야 상영회', maxMembers: 2 });
  });

  it('활성 파티 상한은 계약 코드 그대로 올라간다', async () => {
    fetchMock.mockResolvedValue(
      json(409, {
        code: 'ACTIVE_PARTY_LIMIT_REACHED',
        message: '참여 중인 파티가 너무 많습니다.',
      }),
    );

    await expect(createParty('새 파티', 3)).rejects.toMatchObject({
      code: 'ACTIVE_PARTY_LIMIT_REACHED',
    });
  });
});

describe('API-051 파티 초대', () => {
  it('파티 경로에 userId를 보낸다', async () => {
    fetchMock.mockResolvedValue(
      json(201, {
        invitationId: 501,
        partyId: TEST_PARTY_ID,
        invitee: { userId: 2048, nickname: '무비버디' },
        status: 'PENDING',
        createdAt: '2026-09-10T09:00:00Z',
      }),
    );

    await inviteToParty(TEST_PARTY_ID, 2048);

    const [url, init] = call();
    expect(url).toBe(`/api/v1/parties/${TEST_PARTY_ID}/invitations`);
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ userId: 2048 });
  });

  it('차단 관계는 없는 사람처럼 404다', async () => {
    fetchMock.mockResolvedValue(
      json(404, { code: 'RESOURCE_NOT_FOUND', message: '사용자를 찾을 수 없습니다.' }),
    );

    await expect(inviteToParty(TEST_PARTY_ID, 2048)).rejects.toBeInstanceOf(ApiError);
  });
});

describe('API-052 받은 초대 목록', () => {
  it('items만 돌려준다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { items: [invitation()], nextCursor: null, hasNext: false }),
    );

    const invitations = await getMyInvitations();

    expect(call()[0]).toBe('/api/v1/parties/invitations?size=50');
    expect(invitations[0].party.owner.nickname).toBe('무비버디');
  });

  it('대기 초대가 여러 페이지면 커서를 끝까지 따라가 모두 돌려준다', async () => {
    fetchMock
      .mockResolvedValueOnce(
        json(200, {
          items: [invitation()],
          nextCursor: 'invitation-cursor-2',
          hasNext: true,
        }),
      )
      .mockResolvedValueOnce(
        json(200, {
          items: [invitation({ invitationId: 502 })],
          nextCursor: null,
          hasNext: false,
        }),
      );

    const invitations = await getMyInvitations();

    expect(call(0)[0]).toBe('/api/v1/parties/invitations?size=50');
    expect(call(1)[0]).toBe('/api/v1/parties/invitations?cursor=invitation-cursor-2&size=50');
    expect(invitations.map((item) => item.invitationId)).toEqual([501, 502]);
  });

  it('단건 조회 계약이 없어 목록에서 고른다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { items: [invitation()], nextCursor: null, hasNext: false }),
    );

    expect(await getInvitation(501)).not.toBeNull();
  });

  it('목록에 없는 초대는 null이다. 이미 답했거나 파티가 끝난 경우다', async () => {
    fetchMock.mockResolvedValue(json(200, { items: [], nextCursor: null, hasNext: false }));

    expect(await getInvitation(501)).toBeNull();
  });
});

describe('API-053 · API-054 초대 응답', () => {
  it('수락은 참여한 파티를 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, party()));

    const joined = await acceptInvitation(501);

    expect(call()[0]).toBe('/api/v1/parties/invitations/501/accept');
    expect(call()[1].method).toBe('POST');
    expect(joined.partyId).toBe(TEST_PARTY_ID);
  });

  it('거절은 상태만 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, { invitationId: 501, status: 'DECLINED' }));

    const answered = await declineInvitation(501);

    expect(call()[0]).toBe('/api/v1/parties/invitations/501/decline');
    expect(answered.status).toBe('DECLINED');
  });

  it('이미 답한 초대는 계약 코드 그대로 올라간다', async () => {
    fetchMock.mockResolvedValue(
      json(409, { code: 'INVITATION_NOT_PENDING', message: '이미 처리된 초대입니다.' }),
    );

    await expect(acceptInvitation(501)).rejects.toMatchObject({
      code: 'INVITATION_NOT_PENDING',
    });
  });
});

describe('API-055 · API-056 · API-069 파티 상태 변경', () => {
  const state = {
    partyId: TEST_PARTY_ID,
    status: 'ACTIVE',
    ownerId: 1024,
    closedReason: null,
    closedAt: null,
  };

  it('나가기는 파티 경로에 POST한다', async () => {
    fetchMock.mockResolvedValue(json(200, state));

    await leaveParty(TEST_PARTY_ID);

    expect(call()[0]).toBe(`/api/v1/parties/${TEST_PARTY_ID}/leave`);
    expect(call()[1].method).toBe('POST');
  });

  it('종료는 파티 자체를 DELETE한다', async () => {
    fetchMock.mockResolvedValue(
      json(200, { ...state, status: 'CLOSED', closedReason: 'OWNER_CLOSED' }),
    );

    const closed = await closeParty(TEST_PARTY_ID);

    expect(call()[0]).toBe(`/api/v1/parties/${TEST_PARTY_ID}`);
    expect(call()[1].method).toBe('DELETE');
    expect(closed.closedReason).toBe('OWNER_CLOSED');
  });

  it('내보내기는 멤버 경로를 DELETE한다', async () => {
    fetchMock.mockResolvedValue(json(200, state));

    await kickPartyMember(TEST_PARTY_ID, 2048);

    expect(call()[0]).toBe(`/api/v1/parties/${TEST_PARTY_ID}/members/2048`);
    expect(call()[1].method).toBe('DELETE');
  });

  it('파티장이 아니면 403이 올라간다', async () => {
    fetchMock.mockResolvedValue(json(403, { code: 'FORBIDDEN', message: '권한이 없습니다.' }));

    await expect(closeParty(TEST_PARTY_ID)).rejects.toMatchObject({ code: 'FORBIDDEN' });
  });
});

describe('API-057 파티원 팝콘 조회', () => {
  it('파티 팝콘을 그대로 돌려준다', async () => {
    fetchMock.mockResolvedValue(json(200, partyPopcorn()));

    const popcorn = await getPartyPopcorn(TEST_PARTY_ID);

    expect(call()[0]).toBe(`/api/v1/parties/${TEST_PARTY_ID}/popcorn`);
    expect(popcorn.members).toHaveLength(2);
    // 8종 맛을 항상 모두 준다(API-057 처리 규칙)
    expect(popcorn.members[0].flavors).toHaveLength(8);
  });

  it('멤버가 아니면 404다', async () => {
    fetchMock.mockResolvedValue(
      json(404, { code: 'RESOURCE_NOT_FOUND', message: '파티를 찾을 수 없습니다.' }),
    );

    await expect(getPartyPopcorn(TEST_PARTY_ID)).rejects.toMatchObject({
      code: 'RESOURCE_NOT_FOUND',
    });
  });
});

/*
 * 업무 -802. `refresh`를 붙이지 않으면 일반 조회라 주소에 아무것도 실리지 않아야 한다 —
 * `refresh=false`가 실려도 서버는 같은 뜻으로 받지만, 붙일 때만 붙이는 것이 계약을 읽는 사람이
 * 기대하는 모양이다.
 */
describe('API-058 파티 영화 추천', () => {
  it('일반 조회는 refresh를 싣지 않는다', async () => {
    fetchMock.mockResolvedValue(json(200, partyRecommendations()));

    const set = await getPartyRecommendations(TEST_PARTY_ID);

    expect(call()[0]).toBe(`/api/v1/parties/${TEST_PARTY_ID}/recommendations`);
    expect(set.hasNext).toBe(true);
  });

  it('다시 받기는 refresh=true를 싣는다', async () => {
    fetchMock.mockResolvedValue(json(200, { ...partyRecommendations(), hasNext: false }));

    const set = await getPartyRecommendations(TEST_PARTY_ID, { refresh: true });

    expect(call()[0]).toBe(`/api/v1/parties/${TEST_PARTY_ID}/recommendations?refresh=true`);
    expect(set.hasNext).toBe(false);
  });
});

/*
 * 업무 -826. 세트 단위 커서 페이지이며 화면이 `더 보기`로 한 장씩 받는다. 커서를 싣지
 * 않은 첫 장의 주소에 빈 질의가 붙지 않는지도 함께 고정한다.
 */
describe('API-083 파티 추천 이력 조회', () => {
  it('첫 장은 커서 없이 부른다', async () => {
    fetchMock.mockResolvedValue(json(200, historyPage()));

    const page = await getPartyRecommendationHistory(TEST_PARTY_ID);

    expect(call()[0]).toBe(`/api/v1/parties/${TEST_PARTY_ID}/recommendations/history`);
    expect(call()[1].method).toBe('GET');
    expect(page.items[0].items[0].exposedAt).toBe('2026-09-20T10:01:00Z');
    expect(page.hasNext).toBe(false);
  });

  it('다음 장은 직전 응답의 커서로 부른다', async () => {
    fetchMock.mockResolvedValue(json(200, historyPage()));

    await getPartyRecommendationHistory(TEST_PARTY_ID, { cursor: 'cursor-2', size: 10 });

    expect(call()[0]).toBe(
      `/api/v1/parties/${TEST_PARTY_ID}/recommendations/history?cursor=cursor-2&size=10`,
    );
  });

  it('멤버가 아니면 404다. 파티가 없는 것과 구분하지 않는다', async () => {
    fetchMock.mockResolvedValue(
      json(404, { code: 'RESOURCE_NOT_FOUND', message: '파티를 찾을 수 없습니다.' }),
    );

    await expect(getPartyRecommendationHistory(TEST_PARTY_ID)).rejects.toMatchObject({
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
    });
  });
});
