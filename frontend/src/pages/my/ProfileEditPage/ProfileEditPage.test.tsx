/*
 * 7.2 프로필 편집 — 그 자리에서 한 항목씩 (업무 S15P21E106-305 · -616)
 *
 * 2026-09-18 시안으로 편집이 **프로필 화면 그대로**가 됐다. 고정할 것은 넷이다.
 *
 * 1. 설정 목록이 아니라 프로필 모양이고, 연필을 누른 항목 하나만 편집으로 바뀐다
 * 2. 저장은 항목별이다 — 닉네임은 API-015로, 나머지는 목업 저장소로 간다
 * 3. 고치던 것이 있으면 다른 항목으로 가거나 화면을 떠나기 전에 묻는다
 * 4. 저장한 값이 **7.2-A 미리보기와 7.1에 그대로 보인다** — 화면 하나씩 열어 보면 저장이
 *    끊긴 것을 못 잡으므로(verify-user-paths) 7.2 → 7.2-A · 7.2 → 7.1을 한 라우터에서 걷는다
 *
 * API 계층은 진짜를 쓰고 서버만 가짜로 둔다. 무엇을 보냈는지를 서버 쪽에서 본다.
 * 떠나기를 막는 `useBlocker`가 데이터 라우터에서만 돌아서 `createMemoryRouter`로 그린다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import ProfileEditPage from './index';
import './dialogTestSupport';
import MyDashboardPage from '../MyDashboardPage';
import PublicProfilePage from '../PublicProfilePage';
import { resetSessionForTest, startSession } from '../../../api/session';
import { resetSessionStore } from '../../../mocks/sessionStore';
import { mockMyProfile } from '../../../mocks/user';
import { renderCrop } from '../../../utils/avatarImage';
import { ROUTE_PATTERN } from '../../../routes/paths';
import type { MyProfile } from '../../../types/user';

/*
 * jsdom에는 캔버스가 없어 자르기 결과를 만들 수 없다. 자르는 셈은 `utils/avatarImage.test.ts`가
 * 보고, 여기서는 잘린 JPEG가 API-081로 가는 길만 본다.
 */
vi.mock('../../../utils/avatarImage', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../utils/avatarImage')>()),
  renderCrop: vi.fn(() => Promise.resolve(new Blob(['jpeg'], { type: 'image/jpeg' }))),
}));

/** 서버가 이미 쓰고 있다고 답하는 닉네임 */
const TAKEN = '이미있음';

/** API-040이 주는 취향 키워드. 공개 설정 흐름이 칩을 눌러야 해서 비워 둘 수 없다 */
const KEYWORDS = [
  { code: 'PLANNED_ANTIHERO', label: '계획형 안티히어로' },
  { code: 'FAST_PACED', label: '빠른 전개' },
  { code: 'TWIST_LOVER', label: '반전 선호' },
  { code: 'NOIR_EXPLORER', label: '느와르 탐험가' },
];

let me: MyProfile;
let frameCount: number;
let patches: unknown[];
/** 프로필 사진 요청(API-081 · 082). 올린 파일은 multipart `file` 칸이다 */
let avatarCalls: { method?: string; file: FormDataEntryValue | null }[];
/** API-081이 거절할 때의 응답. 없으면 받아들인다 */
let avatarFailure: { status: number; code: string } | null;

/** 서버가 새 사진에 붙이는 주소 */
const NEW_PHOTO = '/media/avatars/new.jpg';
/** 닉네임 사용 가능 확인(API-016)으로 물어본 값 */
let checks: string[];

function json(body: unknown, status = 200) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

/**
 * 가짜 서버. 내 프로필(API-014 · API-015)과 닉네임 확인(API-016)만 안다. 7.1이 함께 부르는
 * 피드 · 리포트 · 팝콘은 빈 응답이다 — 이 시험은 프로필만 본다.
 */
function stubServer() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url === '/api/v1/users/me' && init?.method === 'PATCH') {
        const body = JSON.parse(String(init.body)) as { nickname?: string };
        patches.push(body);

        if (body.nickname === TAKEN) {
          return json(
            { code: 'NICKNAME_DUPLICATED', message: '이미 사용 중인 닉네임입니다.' },
            409,
          );
        }

        me = { ...me, ...body };

        return json(me);
      }

      if (url === '/api/v1/users/me/avatar') {
        avatarCalls.push({
          method: init?.method,
          file: init?.body instanceof FormData ? init.body.get('file') : null,
        });

        if (init?.method === 'DELETE') {
          me = { ...me, avatarUrl: null };

          return Promise.resolve(new Response(null, { status: 204 }));
        }

        if (avatarFailure) {
          return json({ code: avatarFailure.code, message: '거절' }, avatarFailure.status);
        }

        me = { ...me, avatarUrl: NEW_PHOTO };

        return json({ avatarUrl: NEW_PHOTO });
      }

      // 프로필 화면이 취향 키워드(API-040)를 함께 읽는다(업무 -836). 키워드는 더 이상 목업이
      // 아니라 서버 값이므로 공개 설정 흐름을 보려면 여기서 줘야 한다.
      if (url === '/api/v1/users/me/taste') {
        return json({
          ratingCount: 30,
          insufficientData: false,
          analysis: { genres: [] },
          keywords: KEYWORDS,
          analyzedAt: '2026-09-24T00:00:00Z',
        });
      }

      if (url === '/api/v1/users/me/frames')
        return json({
          items: Array.from({ length: frameCount }, (_, i) => i + 1).map((id) => ({
            frameId: 900 + id,
            movie: {
              movieId: 1000 + id,
              title: `실제 영화 ${id}`,
              posterUrl: `/posters/${id}.jpg`,
            },
            score: 4.5,
            flavorCode: 'FLAVOR_A',
            createdAt: '2026-09-27T00:00:00Z',
          })),
          hasNext: false,
          nextCursor: null,
        });

      // 미리보기는 API-062에서 실제 공개 한줄평 개수도 조회한다(업무 -953).
      if (url === `/api/v1/users/${me.publicId}/profile`) {
        return json({
          user: {
            userId: me.userId,
            nickname: me.nickname,
            avatarUrl: me.avatarUrl,
            visibility: me.visibility,
            frameVisibility: me.frameVisibility,
            popcornVisibility: me.popcornVisibility,
            reviewVisibility: me.reviewVisibility,
          },
          keywords: [],
          stats: { frameCount: me.stats.frameCount, popcornCount: me.stats.popcornCount },
          popcorn: { flavors: [] },
          recentFrames: [],
          publicReviewCount: 0,
          isBlockedByMe: false,
        });
      }
      if (url === '/api/v1/users/me') return json(me);

      if (url.startsWith('/api/v1/users/nickname-availability')) {
        const nickname = new URL(url, 'http://x').searchParams.get('nickname');
        checks.push(nickname ?? '');

        return json({
          nickname,
          available: nickname !== TAKEN,
          reason: nickname === TAKEN ? 'DUPLICATED' : null,
        });
      }

      // 7.1의 나머지 구역. 비어 있어도 프로필 머리는 그려진다
      return json({ items: [], nextCursor: null, hasNext: false, totalCount: 0, flavors: [] });
    }),
  );
}

beforeEach(() => {
  me = { ...mockMyProfile };
  frameCount = 3;
  patches = [];
  checks = [];
  avatarCalls = [];
  avatarFailure = null;
  vi.mocked(renderCrop).mockClear();
  resetSessionStore();
  localStorage.clear();
  resetSessionForTest();
  startSession('token');
  stubServer();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderFlow(start: string = ROUTE_PATTERN.profileEdit) {
  const router = createMemoryRouter(
    [
      { path: ROUTE_PATTERN.my, element: <MyDashboardPage /> },
      { path: ROUTE_PATTERN.profileEdit, element: <ProfileEditPage /> },
      { path: ROUTE_PATTERN.profilePreview, element: <PublicProfilePage mode="preview" /> },
    ],
    { initialEntries: [ROUTE_PATTERN.my, start], initialIndex: 1 },
  );

  /*
   * **주소 갱신을 전환에 맡기지 않는다**(업무 S15P21E106-797).
   *
   * 데이터 라우터는 주소 상태를 `React.startTransition` 안에서 바꾼다(react-router 8
   * `components.js`의 `setState`). 전환은 시간을 쪼개 그리므로, 기계가 바쁘면 React가
   * 5ms마다 양보하며 한 번 그리는 데 macrotask를 수없이 쓴다. 전체 실행에서 일꾼이
   * 밀리면 그 사이가 벌어져 **이동이 5초를 넘도록 커밋되지 않는 일**이 있었다. 깨진 순간의
   * 화면에는 편집 화면이 그대로 있었다 — 무언가가 막은 것이 아니라 아직 그리지 못한 것이다.
   *
   * `test/render.tsx`가 `MemoryRouter`에 건 것과 같은 조치다(업무 -789). 이 파일은
   * `useBlocker` 때문에 데이터 라우터를 직접 만들어 그 도우미를 쓰지 않으므로 여기에 따로 건다.
   * 화면이 하는 일은 그대로이고 주소 갱신의 커밋 시점만 보통 갱신과 같아진다.
   */
  render(<RouterProvider router={router} useTransitions={false} />);

  return router;
}

async function renderEdit() {
  const router = renderFlow();
  await screen.findByRole('button', { name: '닉네임 수정' });

  return router;
}

const pencil = (name: string) => screen.getByRole('button', { name });

describe('7.2 프로필 편집 — 모양', () => {
  it('설정 목록이 아니라 프로필 모양이고, 고칠 자리마다 연필이 있다', async () => {
    await renderEdit();

    expect(screen.getByRole('heading', { name: '프로필 편집', level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '프로필 사진 변경' })).toBeInTheDocument();
    for (const name of [
      '닉네임 수정',
      '소개 수정',
      '공개할 취향 키워드 수정',
      '공개할 취향 필름 수정',
    ]) {
      expect(pencil(name)).toBeInTheDocument();
    }

    // 예전 설정 목록의 읽기 전용 줄(이메일 · 연령대 · 성별)이 없다
    expect(screen.queryByText('이메일')).not.toBeInTheDocument();
    expect(screen.queryByText(me.email)).not.toBeInTheDocument();
    // 처음에는 아무 입력칸도 열려 있지 않다 — 화면 전체가 폼으로 바뀌지 않는다
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('연필을 누른 항목 하나만 편집으로 바뀐다', async () => {
    await renderEdit();

    fireEvent.click(pencil('소개 수정'));

    expect(screen.getByRole('form', { name: '소개 수정' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '자기소개' })).toHaveValue(
      '영화의 장면과 감정을 기록해요',
    );
    expect(screen.getByText('16/40')).toBeInTheDocument();

    // 나머지는 보기 상태 그대로다
    expect(screen.getAllByRole('form')).toHaveLength(1);
    expect(pencil('닉네임 수정')).toBeInTheDocument();
  });
});

describe('7.2 프로필 편집 — 항목별 저장', () => {
  it('소개를 저장하면 그 구역만 보기로 돌아가고 알린다', async () => {
    await renderEdit();

    fireEvent.click(pencil('소개 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '자기소개' }), {
      target: { value: '장면 하나로 하루를 기억해요' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // 편집기가 닫혀야 저장이 끝난 것이다. 그 전에는 입력칸이 같은 글자를 들고 있다
    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument());
    expect(screen.getByText('장면 하나로 하루를 기억해요')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('저장했어요');
    // 소개는 계약이 없어 서버로 나가지 않는다
    expect(patches).toHaveLength(0);
  });

  it('닉네임은 API-015로 보낸다', async () => {
    await renderEdit();

    fireEvent.click(pencil('닉네임 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '닉네임' }), {
      target: { value: '난다킹' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByRole('heading', { name: '난다킹' })).toBeInTheDocument();
    expect(patches).toEqual([{ nickname: '난다킹' }]);
  });

  it('규칙에 맞지 않는 닉네임은 보내지 않고 입력창 아래에 알린다', async () => {
    await renderEdit();

    fireEvent.click(pencil('닉네임 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '닉네임' }), { target: { value: '난' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText(/닉네임은 한글, 영문, 숫자, _ 2~10자예요/)).toBeInTheDocument();
    expect(patches).toHaveLength(0);
  });

  it('서버가 거절하면 입력값을 그대로 두고 까닭을 적는다', async () => {
    await renderEdit();

    fireEvent.click(pencil('닉네임 수정'));
    const input = screen.getByRole('textbox', { name: '닉네임' });
    fireEvent.change(input, { target: { value: TAKEN } });
    // 확인(API-016)을 기다리지 않고 바로 누른다. 저장(API-015)이 다시 검사한다
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText('이미 사용 중인 닉네임입니다.')).toBeInTheDocument();
    expect(input).toHaveValue(TAKEN);
    expect(screen.getByRole('form', { name: '닉네임 수정' })).toBeInTheDocument();
  });

  it('엔터를 두 번 쳐도 요청은 한 번이다', async () => {
    /*
     * 저장 버튼은 첫 누름에 잠기지만, 입력칸에서 친 엔터는 버튼을 거치지 않고 폼을 바로
     * 제출한다. 그 두 번째 제출까지 막는지 본다.
     */
    await renderEdit();

    fireEvent.click(pencil('닉네임 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '닉네임' }), {
      target: { value: '난다킹' },
    });
    const form = screen.getByRole('form', { name: '닉네임 수정' });
    fireEvent.submit(form);
    fireEvent.submit(form);

    await screen.findByRole('heading', { name: '난다킹' });
    expect(patches).toHaveLength(1);
  });
});

describe('7.2 닉네임 사용 가능 확인 (API-016, 업무 -391)', () => {
  /*
   * 입력이 멎으면(400ms) 서버에 묻고, 결과를 입력창 아래에 적는다. 편집 화면을 다시 짠 -616
   * 뒤에도 같은 동작인지 고정한다(!402 리뷰).
   */
  async function typeNickname(value: string) {
    await renderEdit();
    fireEvent.click(pencil('닉네임 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '닉네임' }), { target: { value } });
  }

  it('입력이 멎으면 물어보고, 쓸 수 있으면 알린다', async () => {
    await typeNickname('난다킹');

    expect(await screen.findByText('사용할 수 있는 닉네임이에요')).toBeInTheDocument();
    expect(checks).toEqual(['난다킹']);
  });

  it('이미 쓰는 닉네임이면 오류로 알리고 저장을 보내지 않는다', async () => {
    await typeNickname(TAKEN);

    expect(await screen.findByText('이미 사용 중인 닉네임이에요')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() =>
      expect(screen.getAllByText('이미 사용 중인 닉네임이에요')).not.toHaveLength(0),
    );
    expect(patches).toHaveLength(0);
  });

  it('형식이 틀린 값은 서버에 묻지 않는다', async () => {
    await typeNickname('난!');

    expect(await screen.findByText(/닉네임은 한글, 영문, 숫자, _ 2~10자예요/)).toBeInTheDocument();
    expect(checks).toHaveLength(0);
  });

  it('지금 쓰는 닉네임으로 되돌리면 확인 결과도 사라진다', async () => {
    await typeNickname('난다킹');
    await screen.findByText('사용할 수 있는 닉네임이에요');

    fireEvent.change(screen.getByRole('textbox', { name: '닉네임' }), {
      target: { value: me.nickname },
    });

    expect(screen.queryByText('사용할 수 있는 닉네임이에요')).not.toBeInTheDocument();
    expect(screen.getByText('한글, 영문, 숫자, _ 2~10자')).toBeInTheDocument();
  });
});

describe('7.2 프로필 편집 — 저장하지 않은 변경', () => {
  it('다른 연필을 누르면 묻는다. 계속 편집하면 고치던 값이 남는다', async () => {
    await renderEdit();

    fireEvent.click(pencil('소개 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '자기소개' }), {
      target: { value: '고치는 중' },
    });
    fireEvent.click(pencil('공개할 취향 키워드 수정'));

    const dialog = screen.getByRole('alertdialog', { name: '저장하지 않은 변경사항이 있어요' });
    fireEvent.click(within(dialog).getByRole('button', { name: '계속 편집' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '자기소개' })).toHaveValue('고치는 중');
  });

  it('저장하지 않기를 고르면 버리고 다른 항목을 연다', async () => {
    await renderEdit();

    fireEvent.click(pencil('소개 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '자기소개' }), {
      target: { value: '고치는 중' },
    });
    fireEvent.click(pencil('공개할 취향 키워드 수정'));
    fireEvent.click(screen.getByRole('button', { name: '저장하지 않기' }));

    expect(screen.getByRole('form', { name: '공개할 취향 키워드 수정' })).toBeInTheDocument();
    expect(screen.getByText('영화의 장면과 감정을 기록해요')).toBeInTheDocument();
  });

  it('고치던 것이 있으면 화면을 떠나기 전에 묻는다', async () => {
    const router = await renderEdit();

    fireEvent.click(pencil('소개 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '자기소개' }), {
      target: { value: '고치는 중' },
    });
    fireEvent.click(screen.getByRole('button', { name: '완료' }));

    const dialog = await screen.findByRole('alertdialog');
    expect(router.state.location.pathname).toBe(ROUTE_PATTERN.profileEdit);

    fireEvent.click(within(dialog).getByRole('button', { name: '저장하지 않고 나가기' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(ROUTE_PATTERN.my));
  });

  it('고친 것이 없으면 묻지 않고 떠난다', async () => {
    const router = await renderEdit();

    fireEvent.click(pencil('소개 수정'));
    fireEvent.click(screen.getByRole('button', { name: '완료' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(ROUTE_PATTERN.my));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });
});

describe('7.2 → 7.2-A · 7.1 — 저장한 값이 그대로 보인다', () => {
  it('비공개로 돌린 키워드는 미리보기에서 빠지고, 고친 소개는 보인다', async () => {
    const router = await renderEdit();

    fireEvent.click(pencil('소개 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '자기소개' }), {
      target: { value: '장면 하나로 하루를 기억해요' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument());

    fireEvent.click(pencil('공개할 취향 키워드 수정'));
    fireEvent.click(screen.getByRole('button', { name: /느와르 탐험가/ }));
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: '내 공개 프로필 미리보기' }));

    expect(await screen.findByText('다른 사용자에게 보이는 내 프로필입니다.')).toBeInTheDocument();
    expect(await screen.findByText('장면 하나로 하루를 기억해요')).toBeInTheDocument();
    expect(screen.getByText('계획형 안티히어로')).toBeInTheDocument();
    expect(screen.queryByText('느와르 탐험가')).not.toBeInTheDocument();

    // 미리보기에는 본인 전용 기능도, 자기 자신에게 하는 차단 · 신고도 없다
    expect(screen.queryByRole('button', { name: /수정/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '편집으로 돌아가기' }));
    await waitFor(() => expect(router.state.location.pathname).toBe(ROUTE_PATTERN.profileEdit));
  });

  it('바꾼 닉네임과 소개가 7.1에도 보인다', async () => {
    const router = await renderEdit();

    fireEvent.click(pencil('닉네임 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '닉네임' }), {
      target: { value: '난다킹' },
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await screen.findByRole('heading', { name: '난다킹' });

    fireEvent.click(screen.getByRole('button', { name: '완료' }));
    await waitFor(() => expect(router.state.location.pathname).toBe(ROUTE_PATTERN.my));

    expect(await screen.findByRole('heading', { name: '난다킹' })).toBeInTheDocument();
    expect(screen.getByText('영화의 장면과 감정을 기록해요')).toBeInTheDocument();
  });
});

describe('7.2 프로필 사진', () => {
  it('사진을 누르면 네 가지를 고르는 시트가 뜬다. 올린 사진이 없으면 되돌릴 것이 없다', async () => {
    await renderEdit();

    fireEvent.click(screen.getByRole('button', { name: '프로필 사진 변경' }));

    const sheet = screen.getByRole('dialog', { name: '프로필 사진' });
    expect(within(sheet).getByRole('button', { name: '사진 촬영' })).toBeEnabled();
    expect(within(sheet).getByRole('button', { name: '앨범에서 선택' })).toBeEnabled();
    expect(within(sheet).getByRole('button', { name: '기본 이미지로 변경' })).toBeDisabled();
    expect(within(sheet).getByRole('button', { name: '현재 사진 삭제' })).toBeDisabled();
  });

  it('받지 않는 형식과 너무 큰 사진은 자르기 전에 알린다', async () => {
    await renderEdit();

    const input = screen.getByTestId('avatar-album-input');

    fireEvent.change(input, {
      target: { files: [new File(['gif'], 'me.gif', { type: 'image/gif' })] },
    });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'JPG, PNG, WEBP 사진만 올릴 수 있어요',
    );

    const big = new File(['x'], 'big.png', { type: 'image/png' });
    Object.defineProperty(big, 'size', { value: 11 * 1024 * 1024 });
    fireEvent.change(input, { target: { files: [big] } });
    expect(await screen.findByRole('alert')).toHaveTextContent('10MB 이하의 사진만 올릴 수 있어요');

    expect(screen.queryByRole('dialog', { name: '사진 위치 조정' })).not.toBeInTheDocument();
  });

  it('고른 사진은 올리기 전에 자르기 화면으로 간다', async () => {
    await renderEdit();

    fireEvent.change(screen.getByTestId('avatar-album-input'), {
      target: { files: [new File(['png'], 'me.png', { type: 'image/png' })] },
    });

    expect(await screen.findByRole('dialog', { name: '사진 위치 조정' })).toBeInTheDocument();
    expect(screen.getByRole('slider')).toBeInTheDocument();
  });

  it('현재 사진을 지우면 한 번 더 묻고 API-082로 기본 이미지로 돌아간다', async () => {
    me = { ...me, avatarUrl: '/media/avatars/old.jpg' };
    await renderEdit();

    expect(screen.getByRole('img', { name: `${me.nickname}의 프로필 이미지` })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '프로필 사진 변경' }));
    fireEvent.click(screen.getByRole('button', { name: '현재 사진 삭제' }));
    fireEvent.click(
      within(screen.getByRole('alertdialog', { name: '현재 사진을 삭제할까요?' })).getByRole(
        'button',
        { name: '삭제' },
      ),
    );

    await waitFor(() =>
      expect(
        screen.queryByRole('img', { name: `${me.nickname}의 프로필 이미지` }),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('status')).toHaveTextContent('프로필 사진을 삭제했어요');
    expect(avatarCalls).toEqual([{ method: 'DELETE', file: null }]);
  });

  /** 앨범에서 사진을 골라 자르기 화면에서 `적용`까지 누른다 */
  async function applyPhoto() {
    fireEvent.change(screen.getByTestId('avatar-album-input'), {
      target: { files: [new File(['png'], 'me.png', { type: 'image/png' })] },
    });

    const cropper = await screen.findByRole('dialog', { name: '사진 위치 조정' });
    const image = cropper.querySelector('img');

    if (!image) throw new Error('자르기 사진이 없다');
    Object.defineProperties(image, {
      naturalWidth: { value: 800 },
      naturalHeight: { value: 600 },
    });
    fireEvent.load(image);
    fireEvent.click(within(cropper).getByRole('button', { name: '적용' }));
  }

  it('7.2 → 7.1 잘라 올린 사진은 API-081이 준 주소로 보이고 기기에 남기지 않는다', async () => {
    const router = await renderEdit();

    await applyPhoto();

    expect(await screen.findByRole('status')).toHaveTextContent('프로필 사진을 바꿨어요');
    expect(avatarCalls).toHaveLength(1);
    expect(avatarCalls[0].method).toBe('PUT');
    expect(avatarCalls[0].file).toBeInstanceOf(Blob);
    expect(screen.getByRole('img', { name: `${me.nickname}의 프로필 이미지` })).toHaveAttribute(
      'src',
      NEW_PHOTO,
    );
    expect(localStorage.length).toBe(0);

    // 7.1은 API-014를 다시 읽는다. 다른 기기에서 로그인해도 같은 값이다. 7.1은 사진을 배경에 깐다
    fireEvent.click(screen.getByRole('button', { name: '완료' }));
    await waitFor(() => expect(router.state.location.pathname).toBe(ROUTE_PATTERN.my));
    await waitFor(() =>
      expect(screen.getByTestId('profile-backdrop')).toHaveAttribute('data-photo', 'image'),
    );
    expect(screen.getByTestId('profile-backdrop').style.getPropertyValue('--profile-photo')).toBe(
      `url("${NEW_PHOTO}")`,
    );
  });

  it('서버가 사진을 거절하면(400 · 413) 기존 사진을 둔 채 다른 사진을 고르게 한다', async () => {
    me = { ...me, avatarUrl: '/media/avatars/old.jpg' };
    avatarFailure = { status: 400, code: 'AVATAR_INVALID' };
    await renderEdit();

    await applyPhoto();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '이 사진은 올릴 수 없어요. 다른 사진을 골라 주세요',
    );
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
    expect(screen.getByRole('img', { name: `${me.nickname}의 프로필 이미지` })).toHaveAttribute(
      'src',
      '/media/avatars/old.jpg',
    );

    avatarFailure = { status: 413, code: 'AVATAR_TOO_LARGE' };
    await applyPhoto();

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('10MB 이하의 사진만 올릴 수 있어요'),
    );
  });

  it('저장소가 막히면(503) 기존 사진을 두고, 다시 시도하면 같은 사진을 다시 올린다', async () => {
    avatarFailure = { status: 503, code: 'AVATAR_STORAGE_UNAVAILABLE' };
    await renderEdit();

    await applyPhoto();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '사진을 올리지 못했어요. 기존 사진은 그대로예요.',
    );
    expect(
      screen.queryByRole('img', { name: `${me.nickname}의 프로필 이미지` }),
    ).not.toBeInTheDocument();

    avatarFailure = null;
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(await screen.findByText('프로필 사진을 바꿨어요')).toBeInTheDocument();
    // 다시 자르지 않고 들고 있던 사진을 그대로 보낸다
    expect(renderCrop).toHaveBeenCalledOnce();
    expect(avatarCalls).toHaveLength(2);
    expect(await (avatarCalls[1].file as Blob).text()).toBe('jpeg');
  });
});

it('실제 포스터를 공개 선택하고 저장하면 미리보기에서도 같은 영화가 나온다', async () => {
  const router = await renderEdit();
  expect(screen.getByRole('img', { name: '실제 영화 1' })).toHaveAttribute('src', '/posters/1.jpg');
  fireEvent.click(pencil('공개할 취향 필름 수정'));
  const form = screen.getByRole('form', { name: '공개할 취향 필름 수정' });
  fireEvent.click(within(form).getByRole('button', { name: /실제 영화 1/ }));
  fireEvent.click(within(form).getByRole('button', { name: '저장' }));
  // 저장 안내만 먼저 관찰하고 이동하면 useBlocker의 이전 dirty 상태가 남을 수 있다.
  // 비동기 저장의 렌더와 effect까지 act로 완료한 뒤 실제 화면 전환을 검증한다.
  await screen.findByText('저장했어요');
  await act(async () => {});
  expect(screen.queryByRole('form', { name: '공개할 취향 필름 수정' })).not.toBeInTheDocument();
  expect(screen.queryByRole('img', { name: '실제 영화 1' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '내 공개 프로필 미리보기' }));
  await waitFor(() => expect(router.state.location.pathname).toBe(ROUTE_PATTERN.profilePreview));
  expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  await screen.findByRole('heading', { name: '취향 필름' });
  expect(screen.queryByRole('img', { name: '실제 영화 1' })).not.toBeInTheDocument();
  expect(screen.getByRole('img', { name: '실제 영화 2' })).toHaveAttribute('src', '/posters/2.jpg');
});

describe('S15P21E106-952 공개 필름 관리', () => {
  it('대량 필름은 본문에 최근 3편만 표시하고 관리 창에서 페이지별로 선택한다', async () => {
    frameCount = 30;
    await renderEdit();
    const section = screen.getByRole('heading', { name: '공개된 취향 필름' }).closest('section')!;
    expect(within(section).getAllByRole('img')).toHaveLength(3);
    expect(screen.getByText('공개 중 30편 · 관리')).toBeInTheDocument();
    pencil('공개할 취향 필름 수정').focus();
    fireEvent.click(pencil('공개할 취향 필름 수정'));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getAllByRole('button', { pressed: true })).toHaveLength(13);
    fireEvent.click(within(dialog).getByRole('button', { name: '실제 영화 1' }));
    fireEvent.click(within(dialog).getByRole('button', { name: '다음' }));
    expect(within(dialog).getByText('2 / 3 페이지 · 30편')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: '실제 영화 13' }));
    fireEvent.click(within(dialog).getByRole('button', { name: '저장' }));
    await screen.findByText('저장했어요');
    expect(screen.getByText('공개 중 28편 · 관리')).toBeInTheDocument();
    pencil('공개할 취향 필름 수정').focus();
    fireEvent.click(pencil('공개할 취향 필름 수정'));
    expect(screen.getByRole('button', { name: '실제 영화 1' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getByRole('button', { name: '실제 영화 13' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(pencil('공개할 취향 필름 수정')).toHaveFocus();
  });

  it('검색과 공개 중 필터를 변경해도 선택을 유지하고 취소하면 원래 값으로 돌아간다', async () => {
    frameCount = 25;
    await renderEdit();
    pencil('공개할 취향 필름 수정').focus();
    fireEvent.click(pencil('공개할 취향 필름 수정'));
    const search = screen.getByRole('textbox', { name: '영화 제목 검색' });
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    fireEvent.change(search, { target: { value: '실제 영화 25' } });
    expect(screen.getByText('1 / 1 페이지 · 1편')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '실제 영화 25' }));
    fireEvent.click(screen.getByRole('button', { name: '공개 중 24편' }));
    expect(screen.getByText('검색 결과가 없어요')).toBeInTheDocument();
    fireEvent.change(search, { target: { value: '' } });
    expect(screen.getByText('1 / 2 페이지 · 24편')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '전체 25편' }));
    fireEvent.change(search, { target: { value: '실제 영화 25' } });
    expect(screen.getByRole('button', { name: '실제 영화 25' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    fireEvent.keyDown(search, { key: 'Enter' });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    pencil('공개할 취향 필름 수정').focus();
    fireEvent.click(pencil('공개할 취향 필름 수정'));
    fireEvent.change(screen.getByRole('textbox', { name: '영화 제목 검색' }), {
      target: { value: '실제 영화 25' },
    });
    expect(screen.getByRole('button', { name: '실제 영화 25' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('관리 중 브라우저 뒤로 가기는 저장하지 않은 변경 확인을 제공한다', async () => {
    const router = await renderEdit();
    pencil('공개할 취향 필름 수정').focus();
    fireEvent.click(pencil('공개할 취향 필름 수정'));
    fireEvent.click(screen.getByRole('button', { name: '실제 영화 1' }));
    await router.navigate(-1);
    const confirmation = await screen.findByRole('alertdialog');
    expect(confirmation).toBeInTheDocument();
    // 확인 창이 뜬 뒤 편집 창이 닫히는 것은 다음 렌더에서 끝나므로 기다린다 (S15P21E106-831)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    fireEvent.click(within(confirmation).getByRole('button', { name: '계속 편집' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '실제 영화 1' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });
});

it('필름 저장 실패 시 검색과 선택 초안을 유지하고 다시 저장할 수 있다', async () => {
  await renderEdit();
  fireEvent.click(pencil('공개할 취향 필름 수정'));
  fireEvent.change(screen.getByRole('textbox', { name: '영화 제목 검색' }), {
    target: { value: '실제 영화 1' },
  });
  fireEvent.click(screen.getByRole('button', { name: '실제 영화 1' }));
  const fetchNormally = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((...args) => {
    if (args[0] === '/api/v1/users/me/frames') return json({ code: 'INTERNAL_SERVER_ERROR' }, 500);
    return fetchNormally(...args);
  });
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('저장하지 못했어요');
  expect(screen.getByRole('textbox', { name: '영화 제목 검색' })).toHaveValue('실제 영화 1');
  expect(screen.getByRole('button', { name: '실제 영화 1' })).toHaveAttribute(
    'aria-pressed',
    'false',
  );
  vi.mocked(fetch).mockImplementation(fetchNormally);
  fireEvent.click(screen.getByRole('button', { name: '저장' }));
  await screen.findByText('저장했어요');
  expect(screen.getByText('공개 중 2편 · 관리')).toBeInTheDocument();
});

it('빈 필름 목록도 관리 창을 열고 취소할 수 있다', async () => {
  frameCount = 0;
  await renderEdit();
  fireEvent.click(pencil('공개할 취향 필름 수정'));
  expect(screen.getByText('아직 취향 필름이 없어요')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '취소' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('공개 중 필터의 마지막 페이지를 모두 해제하면 이전 페이지로 돌아간다', async () => {
  frameCount = 13;
  await renderEdit();
  fireEvent.click(pencil('공개할 취향 필름 수정'));
  fireEvent.click(screen.getByRole('button', { name: '공개 중 13편' }));
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  fireEvent.click(screen.getByRole('button', { name: '실제 영화 13' }));
  expect(screen.getByText('1 / 1 페이지 · 12편')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();
});
