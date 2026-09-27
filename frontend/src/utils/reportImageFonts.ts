import anton from '@fontsource/anton/files/anton-latin-400-normal.woff2?url';
import playfairItalic from '@fontsource/playfair-display/files/playfair-display-latin-700-italic.woff2?url';
import playfairBlack from '@fontsource/playfair-display/files/playfair-display-latin-900-normal.woff2?url';
import pretendard from 'pretendard/dist/web/variable/woff2/PretendardVariable.woff2?url';

const FONTS = [
  { family: 'Pretendard Variable', weight: '45 920', style: 'normal', url: pretendard },
  { family: 'Anton', weight: '400', style: 'normal', url: anton },
  { family: 'Playfair Display', weight: '700', style: 'italic', url: playfairItalic },
  { family: 'Playfair Display', weight: '900', style: 'normal', url: playfairBlack },
];

let embeddedFonts: Promise<string> | undefined;

/**
 * 화면과 같은 네 서체를 포함한다. 앱 전체의 동적 한글 서브셋을 수십 개씩
 * 수집하지 않고, 저장할 때만 한글 전체 파일 하나를 읽어 Safari에서도 서체를 보존한다.
 * 실패는 캐시하지 않으며 대체 서체로 저장을 계속하지 않는다.
 */
export function reportFontEmbedCSS(): Promise<string> {
  embeddedFonts ??= Promise.all(
    FONTS.map(async ({ family, weight, style, url }) => {
      const response = await fetch(url, { credentials: 'same-origin' });
      if (!response.ok) throw new Error('ReportFourCut font fetch failed');
      const dataUrl = await readDataUrl(await response.blob());
      // 자산 경로 오류로 200 HTML이 와도 대체 서체로 조용히 저장하지 않는다.
      if (!atob(dataUrl.split(',')[1].slice(0, 8)).startsWith('wOF2')) {
        throw new Error('ReportFourCut font response is invalid');
      }
      return `@font-face { font-family: '${family}'; font-weight: ${weight}; font-style: ${style}; src: url('${dataUrl}') format('woff2'); }`;
    }),
  )
    .then((rules) => rules.join('\n'))
    .catch((error: unknown) => {
      embeddedFonts = undefined;
      throw error;
    });
  return embeddedFonts;
}

export function readDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('ReportFourCut resource conversion failed'));
    reader.readAsDataURL(blob);
  });
}
