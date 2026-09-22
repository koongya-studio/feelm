/*
 * 7.8-A 계정 관리의 소셜 계정 연결 (업무 S15P21E106-799)
 *
 * 화면에서 드러나지 않는 것을 고정한다.
 * - **연결 여부는 API-014 `linkedProviders`가 정한다.** 대문자로 오는 값(`KAKAO`)을 제공자로
 *   읽어야 한다
 * - **자격 증명이 없는 제공자는 줄이 없다.** 눌러도 `UNSUPPORTED_PROVIDER`라 보이면 안 된다
 * - **콜백에서 돌아온 코드는 한 번만 보낸다.** StrictMode의 두 번째 효과가 같은 코드를 다시
 *   보내면 두 번째는 반드시 실패한다
 * - **취소 · 오류마다 다른 문구다.** 계약의 코드별로 무엇을 하면 되는지 알려야 한다
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import AccountManagePage from './index';
import { ApiError } from '../../../api/apiError';
import { renderWithRouter } from '../../../test/render';
import { mockMyProfile } from '../../../mocks/user';
import type { MyProfile } from '../../../types/user';

vi.mock('../../../api/users', () => ({ getMyProfile: vi.fn() }));
vi.mock('../../../api/auth', () => ({ linkSocialAccount: vi.fn() }));
vi.mock('../../../utils/oauth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../utils/oauth')>()),
  startOAuth: vi.fn(),
}));

const { getMyProfile } = await import('../../../api/users');
const { linkSocialAccount } = await import('../../../api/auth');
const { startOAuth } = await import('../../../utils/oauth');

function profile(overrides: Partial<MyProfile> = {}): MyProfile {
  return { ...mockMyProfile, ...overrides };
}

/** 콜백이 라우터 state에 실어 보내는 값 그대로 그린다 */
function renderReturned(link: unknown) {
  return renderWithRouter(<AccountManagePage />, {
    route: '/my/settings/account',
    state: { link },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubEnv('VITE_KAKAO_CLIENT_ID', 'kakao-client');
  vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'google-client');
  vi.stubEnv('VITE_NAVER_CLIENT_ID', '');
  vi.mocked(getMyProfile).mockResolvedValue(profile({ linkedProviders: ['KAKAO'] }));
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('소셜 계정 묶음', () => {
  it('연결된 제공자는 연결됨, 아닌 제공자는 연결하기다. 자격 증명 없는 제공자는 줄이 없다', async () => {
    renderWithRouter(<AccountManagePage />, { route: '/my/settings/account' });

    expect(await screen.findByRole('heading', { name: '소셜 계정' })).toBeInTheDocument();
    expect(screen.getByText('카카오')).toBeInTheDocument();
    expect(screen.getByText('연결됨')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '구글 연결하기' })).toBeInTheDocument();
    expect(screen.queryByText(/네이버/)).not.toBeInTheDocument();
  });

  it('열린 제공자가 하나도 없으면 묶음 자체가 없다', async () => {
    vi.stubEnv('VITE_KAKAO_CLIENT_ID', '');
    vi.stubEnv('VITE_GOOGLE_CLIENT_ID', '');

    renderWithRouter(<AccountManagePage />, { route: '/my/settings/account' });

    expect(await screen.findByText('이메일')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '소셜 계정' })).not.toBeInTheDocument();
  });

  it('연결하기를 누르면 link 의도로 제공자 동의 화면에 나간다', async () => {
    renderWithRouter(<AccountManagePage />, { route: '/my/settings/account' });

    fireEvent.click(await screen.findByRole('button', { name: '구글 연결하기' }));

    expect(vi.mocked(startOAuth)).toHaveBeenCalledWith('google', 'link');
    expect(screen.getByText('연결하는 중…')).toBeInTheDocument();
  });
});

describe('콜백에서 돌아왔을 때', () => {
  const returned = {
    provider: 'google',
    code: 'auth-code',
    redirectUri: 'http://localhost:3000/oauth/callback',
  };

  it('API-009를 한 번 부르고 프로필을 다시 읽어 연결됨으로 바꾼다', async () => {
    vi.mocked(linkSocialAccount).mockResolvedValue({
      provider: 'GOOGLE',
      linkedAt: '2026-09-22T00:00:00Z',
    });
    vi.mocked(getMyProfile)
      .mockResolvedValueOnce(profile({ linkedProviders: ['KAKAO'] }))
      .mockResolvedValue(profile({ linkedProviders: ['KAKAO', 'GOOGLE'] }));

    renderReturned(returned);

    expect(await screen.findByRole('status')).toHaveTextContent('구글 계정을 연결했어요.');
    expect(vi.mocked(linkSocialAccount)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(linkSocialAccount)).toHaveBeenCalledWith(
      'google',
      'auth-code',
      'http://localhost:3000/oauth/callback',
    );
    await waitFor(() => expect(screen.getAllByText('연결됨')).toHaveLength(2));
    expect(screen.queryByRole('button', { name: '구글 연결하기' })).not.toBeInTheDocument();
    // 코드는 1회용이라 주소의 state를 지운다
    expect(screen.getByTestId('location')).toHaveTextContent('/my/settings/account');
  });

  it.each([
    ['PROVIDER_ALREADY_LINKED', '이미 연결된 소셜 계정이에요.'],
    [
      'OAUTH_ACCOUNT_ALREADY_LINKED',
      '이 소셜 계정은 다른 Feelm 계정에 연결되어 있어요. 다른 소셜 계정으로 시도해 주세요.',
    ],
    ['UNSUPPORTED_PROVIDER', '지금은 이 소셜 계정을 연결할 수 없어요.'],
    ['OAUTH_CODE_INVALID', '연결이 끝나지 않았어요. 다시 시도해 주세요.'],
    ['VALIDATION_FAILED', '연결 설정이 올바르지 않아요. 잠시 후 다시 시도해 주세요.'],
    ['OAUTH_PROVIDER_ERROR', '소셜 계정 제공자와 연결하지 못했어요. 잠시 후 다시 시도해 주세요.'],
  ])('%s이면 그에 맞는 문구로 알리고 연결하기로 돌아온다', async (code, message) => {
    vi.mocked(linkSocialAccount).mockRejectedValue(new ApiError(409, code, '서버 문구'));

    renderReturned(returned);

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(screen.getByRole('button', { name: '구글 연결하기' })).toBeInTheDocument();
  });

  it('모르는 오류는 서버 문구를 그대로 보여 준다', async () => {
    vi.mocked(linkSocialAccount).mockRejectedValue(
      new ApiError(500, 'INTERNAL_ERROR', '잠시 문제가 생겼어요.'),
    );

    renderReturned(returned);

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 문제가 생겼어요.');
  });

  it('취소하고 돌아오면 그렇게 알리고 API-009를 부르지 않는다', async () => {
    renderReturned({ provider: 'google', cancelled: true });

    expect(await screen.findByRole('alert')).toHaveTextContent('구글 계정 연결을 취소했어요.');
    expect(vi.mocked(linkSocialAccount)).not.toHaveBeenCalled();
  });

  it('모양이 다른 state는 없는 것으로 본다', async () => {
    renderReturned({ provider: 'google' });

    expect(await screen.findByRole('button', { name: '구글 연결하기' })).toBeInTheDocument();
    expect(vi.mocked(linkSocialAccount)).not.toHaveBeenCalled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
