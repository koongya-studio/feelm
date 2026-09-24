/*
 * 저장용 취향 4컷.
 *
 * 별도 그림 코드를 두지 않고 화면에 보이는 `ReportFourCut` DOM을 그대로 PNG로 바꾼다.
 * CSS·서체·로고·사진·캐릭터 에셋이 바뀌면 저장 결과도 자동으로 함께 바뀐다.
 *
 * - 앞면(`captureReportImage`)은 API-061 규격인 1080×3268로 찍는다
 * - 뒷면(`captureReportBackImage`)은 칸 넷을 모두 뒤집은 같은 DOM이다. 뒷면 높이가 내용만큼
 *   늘어나므로 폭만 1080으로 맞추고 높이는 화면 비율을 따른다. 공유 메뉴로만 넘기고 서버에
 *   올리지 않는다(업무 -849)
 */
import { toBlob } from 'html-to-image';

export const REPORT_IMAGE_WIDTH = 1080;
export const REPORT_IMAGE_HEIGHT = 3268;
const CAPTURE_TIMEOUT_MS = 15_000;
const DOWNLOAD_TIMEOUT_MS = 30_000;

/** 웹폰트와 사진 배치가 끝난 뒤 화면의 4컷 DOM을 고해상도 PNG로 캡처한다. */
export function captureReportImage(node: HTMLElement): Promise<Blob> {
  return withTimeout(
    captureReadyReportImage(node, () => REPORT_IMAGE_HEIGHT),
    'ReportFourCut PNG capture timed out',
  );
}

/** 칸을 뒤집어 둔 4컷 DOM을 폭 1080, 화면과 같은 비율의 PNG로 캡처한다. */
export function captureReportBackImage(node: HTMLElement): Promise<Blob> {
  return withTimeout(
    captureReadyReportImage(node, (width, height) =>
      Math.round((REPORT_IMAGE_WIDTH * height) / width),
    ),
    'ReportFourCut back PNG capture timed out',
  );
}

async function captureReadyReportImage(
  node: HTMLElement,
  canvasHeightOf: (width: number, height: number) => number,
): Promise<Blob> {
  assertImagesAvailable(node);
  if (document.fonts) {
    await document.fonts.ready;
  }
  await Promise.all(Array.from(node.querySelectorAll('img')).map((image) => image.decode()));
  await nextPaint();
  assertImagesAvailable(node);

  if (
    Array.from(node.querySelectorAll('img')).some(
      (image) => !image.complete || image.naturalWidth <= 0,
    )
  ) {
    throw new Error('ReportFourCut image loading failed');
  }

  const width = node.scrollWidth;
  const height = node.scrollHeight;
  if (width <= 0 || height <= 0) throw new Error('ReportFourCut is not laid out');

  const png = await toBlob(node, {
    width,
    height,
    canvasWidth: REPORT_IMAGE_WIDTH,
    canvasHeight: canvasHeightOf(width, height),
    backgroundColor: '#0c0c0d',
    cacheBust: true,
    pixelRatio: 1,
  });

  if (!png || png.type !== 'image/png') throw new Error('ReportFourCut PNG capture failed');
  return png;
}

/** API-061의 서명 URL을 브라우저 다운로드로 연결한다. */
export async function downloadReportImage(downloadUrl: string, reportId: number): Promise<void> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), DOWNLOAD_TIMEOUT_MS);
  let png: Blob;
  try {
    const response = await fetch(downloadUrl, {
      credentials: 'same-origin',
      signal: controller.signal,
    });
    if (!response.ok) throw new Error('ReportFourCut PNG download failed');

    png = await response.blob();
  } finally {
    window.clearTimeout(timeout);
  }
  if (png.type !== 'image/png') throw new Error('ReportFourCut PNG response is invalid');

  downloadBlob(png, `feelm-report-${reportId}.png`);
}

/** 손에 든 파일을 브라우저 다운로드로 내려받는다. */
export function downloadBlob(blob: Blob, fileName: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName;
  link.hidden = true;
  try {
    document.body.append(link);
    link.click();
  } finally {
    link.remove();
    // 클릭 직후 해제하면 일부 브라우저가 읽기 전에 URL이 사라질 수 있다.
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000);
  }
}

function assertImagesAvailable(node: HTMLElement): void {
  if (node.querySelector('[data-image-error="true"]')) {
    throw new Error('ReportFourCut image loading failed');
  }
}

function nextPaint(): Promise<void> {
  return new Promise((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
  });
}

function withTimeout<T>(promise: PromiseLike<T>, message: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(() => reject(new Error(message)), CAPTURE_TIMEOUT_MS);
    Promise.resolve(promise).then(
      (value) => {
        window.clearTimeout(timeout);
        resolve(value);
      },
      (error: unknown) => {
        window.clearTimeout(timeout);
        reject(error instanceof Error ? error : new Error(message));
      },
    );
  });
}
