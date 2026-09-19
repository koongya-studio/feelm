import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { disableWebPush, enableWebPush, getWebPushStatus } from '../../../pwa/webPush';
import WebPushSection from './WebPushSection';

vi.mock('../../../pwa/webPush', () => ({
  getWebPushStatus: vi.fn(),
  enableWebPush: vi.fn(),
  disableWebPush: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(getWebPushStatus).mockReset().mockResolvedValue('off');
  vi.mocked(enableWebPush).mockReset().mockResolvedValue('on');
  vi.mocked(disableWebPush).mockReset().mockResolvedValue(undefined);
});

async function pushSwitch() {
  return screen.findByRole('switch', { name: '이 기기로 푸시 받기' });
}

describe('7.8-C 이 기기로 푸시 받기', () => {
  it('켜면 구독하고 켜진 상태와 안내를 보인다', async () => {
    render(<WebPushSection />);
    const toggle = await pushSwitch();

    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(toggle).toHaveAccessibleDescription(
      '켜면 파티 초대와 초대 수락을 이 기기로 알려 드려요',
    );

    fireEvent.click(toggle);

    expect(enableWebPush).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
    expect(toggle).toHaveAccessibleDescription(
      '파티 초대와 초대 수락을 이 기기로 바로 알려 드려요',
    );
  });

  it('끄면 구독을 해제한다', async () => {
    vi.mocked(getWebPushStatus).mockResolvedValue('on');

    render(<WebPushSection />);
    fireEvent.click(await pushSwitch());

    expect(disableWebPush).toHaveBeenCalledTimes(1);
    await waitFor(async () => expect(await pushSwitch()).toHaveAttribute('aria-checked', 'false'));
  });

  it('권한을 거부하면 스위치를 잠그고 브라우저 설정을 안내한다', async () => {
    vi.mocked(enableWebPush).mockResolvedValue('denied');

    render(<WebPushSection />);
    const toggle = await pushSwitch();

    fireEvent.click(toggle);

    await waitFor(() => expect(toggle).toBeDisabled());
    expect(toggle).toHaveAccessibleDescription(/브라우저 설정에서 허용해 주세요/);
  });

  it.each([
    ['install-required', /홈 화면에 추가한 Feelm 앱에서/],
    ['unsupported', /지원하지 않아요/],
    ['unavailable', /지금은 푸시 알림을 보낼 수 없어요/],
  ] as const)('%s이면 스위치를 잠그고 이유를 적는다', async (status, note) => {
    vi.mocked(getWebPushStatus).mockResolvedValue(status);

    render(<WebPushSection />);
    const toggle = await pushSwitch();

    expect(toggle).toBeDisabled();
    expect(toggle).toHaveAccessibleDescription(note);
  });

  it('연결이 끊긴 구독은 다시 켜도록 안내한다', async () => {
    vi.mocked(getWebPushStatus).mockResolvedValue('expired');

    render(<WebPushSection />);
    const toggle = await pushSwitch();

    expect(toggle).toBeEnabled();
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(toggle).toHaveAccessibleDescription('이 기기와 푸시 연결이 끊겼어요. 다시 켜 주세요');
  });

  it('네트워크 실패를 알리고 실제 상태로 돌아간다', async () => {
    vi.mocked(enableWebPush).mockRejectedValue(new TypeError('Failed to fetch'));

    render(<WebPushSection />);
    const toggle = await pushSwitch();

    fireEvent.click(toggle);

    expect(await screen.findByRole('alert')).toHaveTextContent('푸시 설정을 바꾸지 못했어요');
    expect(toggle).toHaveAttribute('aria-checked', 'false');
  });

  it('끄기가 실패해도 알리고 실제 상태(켜짐)로 돌아간다', async () => {
    vi.mocked(getWebPushStatus).mockResolvedValue('on');
    vi.mocked(disableWebPush).mockRejectedValue(new TypeError('Failed to fetch'));

    render(<WebPushSection />);
    const toggle = await pushSwitch();

    fireEvent.click(toggle);

    expect(await screen.findByRole('alert')).toHaveTextContent('푸시 설정을 바꾸지 못했어요');
    expect(disableWebPush).toHaveBeenCalledTimes(1);
    expect(toggle).toHaveAttribute('aria-checked', 'true');
  });
});
