import { beforeEach, describe, expect, it, vi } from 'vitest';

describe('리포트 캡처의 독립 폰트 임베딩', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it('한글 전체 폰트 하나와 실제 리포트의 영문 서체만 포함하며 다음 캡처는 재사용한다', async () => {
    const fetchFont = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['wOF2font'], { type: 'font/woff2' }),
    } as Response);
    const { reportFontEmbedCSS } = await import('./reportImageFonts');
    const css = await reportFontEmbedCSS();
    expect(css.match(/@font-face/g)).toHaveLength(4);
    expect(css).toContain("font-family: 'Pretendard Variable'");
    expect(css).toContain("font-family: 'Anton'");
    expect(css).toContain("font-family: 'Playfair Display'; font-weight: 700; font-style: italic");
    expect(css).toContain("font-family: 'Playfair Display'; font-weight: 900; font-style: normal");
    expect(css.match(/data:font\/woff2;base64,/g)).toHaveLength(4);
    expect(fetchFont.mock.calls.flatMap(([url]) => String(url))).not.toEqual(
      expect.arrayContaining([expect.stringContaining('subset')]),
    );
    expect(await reportFontEmbedCSS()).toBe(css);
    expect(fetchFont).toHaveBeenCalledTimes(4);
  });

  it('폰트 실패는 대체 서체로 성공시키거나 캐시하지 않고 다음 요청에서 다시 읽는다', async () => {
    const fetchFont = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: false } as Response);
    const { reportFontEmbedCSS } = await import('./reportImageFonts');
    await expect(reportFontEmbedCSS()).rejects.toThrow('font fetch failed');
    fetchFont.mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['wOF2font'], { type: 'font/woff2' }),
    } as Response);
    expect(await reportFontEmbedCSS()).toContain('data:font/woff2;base64,');
    expect(fetchFont).toHaveBeenCalledTimes(8);
  });

  it('폰트 대신 200 HTML이 와도 폴백 서체로 저장하지 않는다', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      blob: async () => new Blob(['<!doctype html>'], { type: 'text/html' }),
    } as Response);
    const { reportFontEmbedCSS } = await import('./reportImageFonts');
    await expect(reportFontEmbedCSS()).rejects.toThrow('font response is invalid');
  });
});
