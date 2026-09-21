/*
 * 주소 공유 (업무 S15P21E106-767).
 *
 * 2026-09-21 배포본 점검에서 데스크톱의 `프로필 공유`가 아무 반응이 없었다. 데스크톱 Chrome에도
 * `navigator.share`가 있어 그것만 믿고 불렀고, 운영체제 공유 창이 뜨지 않는 실패를 `취소`와
 * 함께 삼켰다. 여기서는 기기의 입력 방식(`pointer: coarse`)을 바꿔 가며 어느 길로 가는지,
 * 그리고 **결과가 늘 돌아오는지** 본다.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { shareLink } from './share';

const LINK = { title: '영화광님의 Feelm 프로필', url: 'https://feelm.test/app/users/7' };

/** 손가락이 주 입력인 기기(휴대폰)인지 정한다 */
function pointer(coarse: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({ matches: coarse && query === '(pointer: coarse)', media: query }) as MediaQueryList,
  );
}

function stubShare(impl: () => Promise<void>) {
  const share = vi.fn(impl);
  Object.defineProperty(navigator, 'share', { value: share, configurable: true });

  return share;
}

function stubClipboard(impl: () => Promise<void>) {
  const writeText = vi.fn(impl);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

  return writeText;
}

afterEach(() => {
  vi.restoreAllMocks();
  Reflect.deleteProperty(navigator, 'share');
  Reflect.deleteProperty(navigator, 'clipboard');
});

describe('주소 공유', () => {
  it('데스크톱은 공유 메뉴가 있다고 해도 열지 않고 주소를 복사한다', async () => {
    pointer(false);
    const share = stubShare(() => new Promise(() => undefined));
    const writeText = stubClipboard(async () => undefined);

    await expect(shareLink(LINK)).resolves.toBe('copied');
    expect(share).not.toHaveBeenCalled();
    expect(writeText).toHaveBeenCalledWith(LINK.url);
  });

  it('휴대폰은 기기의 공유 메뉴로 보낸다', async () => {
    pointer(true);
    const share = stubShare(async () => undefined);
    const writeText = stubClipboard(async () => undefined);

    await expect(shareLink(LINK)).resolves.toBe('shared');
    expect(share).toHaveBeenCalledWith(LINK);
    expect(writeText).not.toHaveBeenCalled();
  });

  it('공유 메뉴를 닫으면 취소다. 복사하지 않는다', async () => {
    pointer(true);
    stubShare(async () => {
      throw new DOMException('closed', 'AbortError');
    });
    const writeText = stubClipboard(async () => undefined);

    await expect(shareLink(LINK)).resolves.toBe('cancelled');
    expect(writeText).not.toHaveBeenCalled();
  });

  it('공유 메뉴가 취소가 아닌 이유로 거절되면 복사로 넘어간다', async () => {
    pointer(true);
    stubShare(async () => {
      throw new DOMException('policy', 'NotAllowedError');
    });
    stubClipboard(async () => undefined);

    await expect(shareLink(LINK)).resolves.toBe('copied');
  });

  it('공유 메뉴가 없는 휴대폰도 복사한다', async () => {
    pointer(true);
    stubClipboard(async () => undefined);

    await expect(shareLink(LINK)).resolves.toBe('copied');
  });

  it('복사도 막혀 있으면 실패를 돌려준다 — 조용히 끝내지 않는다', async () => {
    pointer(false);
    stubClipboard(async () => {
      throw new DOMException('denied', 'NotAllowedError');
    });

    await expect(shareLink(LINK)).resolves.toBe('failed');
  });
});
