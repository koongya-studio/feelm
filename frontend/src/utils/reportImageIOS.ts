import { toSvg } from 'html-to-image';
import styles from '../components/ReportFourCut/ReportFourCut.module.css';
import { assertCaptureActive, waitForCapture } from './reportImageCancellation';

type Options = NonNullable<Parameters<typeof toSvg>[1]>;
type Rect = { x: number; y: number; width: number; height: number };
type Photo = {
  image: HTMLImageElement;
  data: string;
  frame: Rect;
  fitted: Rect;
  z: number;
  filter: string;
  mask: string;
};

/** 사진은 Canvas에 직접 그린다. SVG foreignObject에 이미지를 넣지 않는다. */
export async function renderIOSReportImage(
  node: HTMLElement,
  images: HTMLImageElement[],
  data: string[],
  options: Options,
  signal?: AbortSignal,
): Promise<Blob> {
  const source = await waitForCapture(
    () =>
      toSvg(node, {
        ...options,
        filter: (child) =>
          !(child instanceof HTMLImageElement) && (options.filter?.(child) ?? true),
      }),
    signal,
  );
  const xml = new DOMParser().parseFromString(
    decodeURIComponent(source.split(',').slice(1).join(',')),
    'image/svg+xml',
  );
  if (xml.querySelector('parsererror')) throw new Error('ReportFourCut SVG parsing failed');
  const svg = xml.documentElement;
  const foreground = svg.querySelector('foreignObject');
  if (!foreground) throw new Error('ReportFourCut SVG content is missing');
  const base = foreground.cloneNode(true) as Element;
  clearText(base);
  base.querySelectorAll('style').forEach((style) => style.remove());
  const rootElement = foreground.firstElementChild as HTMLElement | null;
  rootElement?.style.setProperty('background', 'none', 'important');
  foreground.querySelectorAll<HTMLElement>(`.${styles.strip}, [data-cut]`).forEach((element) => {
    element.style.setProperty('background', 'none', 'important');
  });
  const gradients = foreground.cloneNode(true) as Element;
  clearText(gradients);
  gradients.querySelectorAll('style').forEach((style) => {
    if (style.textContent?.includes('@font-face')) style.remove();
  });
  foreground.querySelectorAll('style').forEach((style) => {
    if (style.textContent?.includes('::after')) style.remove();
  });

  const root = node.getBoundingClientRect();
  const photos: Photo[] = images.map((image, index) => {
    const parent = image.parentElement,
      cut = image.closest('[data-cut]');
    if (!parent || !cut) throw new Error('ReportFourCut image layer is missing');
    const computed = getComputedStyle(image),
      parentStyle = getComputedStyle(parent);
    const box = relativeRect(image.getBoundingClientRect(), root);
    const frame = intersection(
      relativeRect(parent.getBoundingClientRect(), root),
      relativeRect(cut.getBoundingClientRect(), root),
    );
    if (frame.width <= 0 || frame.height <= 0)
      throw new Error('ReportFourCut image is not laid out');
    return {
      image,
      data: data[index],
      frame,
      fitted: fittedRect(
        box,
        image.naturalWidth,
        image.naturalHeight,
        computed.objectFit,
        computed.objectPosition,
      ),
      z: Number(parentStyle.zIndex) || 0,
      filter: computed.filter,
      mask: parentStyle.maskImage,
    };
  });
  const genreCutout = photos.find(
    (photo) =>
      photo.image.closest('[data-cut]')?.getAttribute('data-cut') === 'genre' &&
      photo.image.parentElement?.classList.contains(styles.figure),
  );
  let lowerName: Element | undefined;
  if (genreCutout) {
    const name = foreground.querySelector<HTMLElement>(`[data-cut="genre"] .${styles.nameArea}`);
    if (name) {
      lowerName = foreground.cloneNode(true) as Element;
      lowerName
        .querySelectorAll<HTMLElement>(
          `.${styles.head}, .${styles.foot}, .${styles.label}, .${styles.caption}`,
        )
        .forEach((element) => {
          hideTree(element);
        });
      hideTree(name);
    }
  }

  const canvas = document.createElement('canvas');
  canvas.width = options.canvasWidth ?? 1080;
  canvas.height = options.canvasHeight ?? 3268;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('ReportFourCut PNG conversion failed');
  const sx = canvas.width / (options.width ?? node.scrollWidth);
  const sy = canvas.height / (options.height ?? node.scrollHeight);
  context.fillStyle = options.backgroundColor ?? '#0c0c0d';
  context.fillRect(0, 0, canvas.width, canvas.height);
  const decoded = new Map<string, Promise<HTMLImageElement>>();
  async function paintLayer(layer: Element, clip?: Rect) {
    const documentSvg = svg.cloneNode(false) as Element;
    documentSvg.append(layer);
    const uri = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(new XMLSerializer().serializeToString(documentSvg))}`;
    const raster = await loadImage(uri, signal);
    context!.save();
    if (clip) {
      context!.beginPath();
      context!.rect(clip.x * sx, clip.y * sy, clip.width * sx, clip.height * sy);
      context!.clip();
    }
    context!.drawImage(raster, 0, 0, canvas.width, canvas.height);
    context!.restore();
  }
  async function paintPhotos(selected: Photo[]) {
    for (const photo of selected) {
      let resource = decoded.get(photo.data);
      if (!resource) {
        resource = loadImage(photo.data, signal);
        decoded.set(photo.data, resource);
      }
      const raster = await resource;
      const patch = document.createElement('canvas');
      patch.width = Math.max(1, Math.round(photo.frame.width * sx));
      patch.height = Math.max(1, Math.round(photo.frame.height * sy));
      const pixels = patch.getContext('2d');
      if (!pixels) throw new Error('ReportFourCut photo conversion failed');
      pixels.drawImage(
        raster,
        (photo.fitted.x - photo.frame.x) * sx,
        (photo.fitted.y - photo.frame.y) * sy,
        photo.fitted.width * sx,
        photo.fitted.height * sy,
      );
      const contrast = Number(photo.filter.match(/contrast\(([^)]+)\)/)?.[1] ?? 1);
      if (photo.filter.includes('grayscale(1)') || contrast !== 1) {
        const content = pixels.getImageData(0, 0, patch.width, patch.height);
        applyPhotoFilter(content.data, photo.filter.includes('grayscale(1)'), contrast);
        pixels.putImageData(content, 0, 0);
      }
      if (photo.mask && photo.mask !== 'none') {
        const stops = [
          ...photo.mask.matchAll(/(transparent|rgba?\([^)]*\)|#[\da-f]+)\s+(\d+(?:\.\d+)?)%/gi),
        ];
        if (!photo.mask.startsWith('linear-gradient(90deg,') || stops.length < 2)
          throw new Error('ReportFourCut image mask is unsupported');
        const fade = pixels.createLinearGradient(0, 0, patch.width, 0);
        stops.forEach(([, color, offset]) => {
          const alpha =
            color === 'transparent'
              ? '0'
              : color.startsWith('rgba(')
                ? (color.match(/,\s*([\d.]+)\)$/)?.[1] ?? '1')
                : '1';
          fade.addColorStop(Number(offset) / 100, `rgba(0, 0, 0, ${alpha})`);
        });
        pixels.globalCompositeOperation = 'destination-in';
        pixels.fillStyle = fade;
        pixels.fillRect(0, 0, patch.width, patch.height);
      }
      context!.drawImage(
        patch,
        photo.frame.x * sx,
        photo.frame.y * sy,
        photo.frame.width * sx,
        photo.frame.height * sy,
      );
      patch.width = patch.height = 0;
    }
  }
  await paintLayer(base);
  await paintPhotos(photos.filter((photo) => photo.z < 2));
  await paintLayer(gradients);
  if (lowerName && genreCutout) await paintLayer(lowerName, genreCutout.frame);
  await paintPhotos(photos.filter((photo) => photo.z >= 2));
  await paintLayer(foreground);
  return waitForCapture(
    () =>
      new Promise<Blob>((resolve, reject) =>
        canvas.toBlob((png) => {
          if (png) resolve(png);
          else reject(new Error('ReportFourCut PNG capture failed'));
        }, 'image/png'),
      ),
    signal,
  );
}

async function loadImage(uri: string, signal?: AbortSignal): Promise<HTMLImageElement> {
  assertCaptureActive(signal);
  const image = new Image();
  image.src = uri;
  try {
    await waitForCapture(() => image.decode(), signal);
  } catch (error) {
    assertCaptureActive(signal);
    if (!image.complete || image.naturalWidth <= 0) throw error;
  }
  await waitForCapture(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())),
    signal,
  );
  return image;
}
function relativeRect(box: DOMRect, root: DOMRect): Rect {
  return { x: box.left - root.left, y: box.top - root.top, width: box.width, height: box.height };
}
function intersection(a: Rect, b: Rect): Rect {
  const x = Math.max(a.x, b.x),
    y = Math.max(a.y, b.y);
  return {
    x,
    y,
    width: Math.max(0, Math.min(a.x + a.width, b.x + b.width) - x),
    height: Math.max(0, Math.min(a.y + a.height, b.y + b.height) - y),
  };
}
function clearText(layer: Element) {
  const walker = layer.ownerDocument.createTreeWalker(layer, NodeFilter.SHOW_TEXT);
  let text: Node | null;
  while ((text = walker.nextNode())) {
    if (text.parentElement?.tagName.toLowerCase() !== 'style') text.textContent = '';
  }
  layer.querySelectorAll<SVGElement>('svg').forEach((element) => {
    hideTree(element);
  });
}
// html-to-image는 자식에도 계산된 visibility:visible을 복사하므로 부모만 숨겨서는 안 된다.
function hideTree(element: Element) {
  [element, ...element.querySelectorAll('*')].forEach((child) => {
    (child as HTMLElement | SVGElement).style.setProperty('visibility', 'hidden', 'important');
  });
}
export function fittedRect(
  box: Rect,
  width: number,
  height: number,
  fit: string,
  position: string,
): Rect {
  const scale =
    fit === 'contain'
      ? Math.min(box.width / width, box.height / height)
      : Math.max(box.width / width, box.height / height);
  const [x = '50%', y = '50%'] = position.split(/\s+/);
  const offset = (value: string, space: number) =>
    value.endsWith('%') ? (space * parseFloat(value)) / 100 : parseFloat(value);
  return {
    x: box.x + offset(x, box.width - width * scale),
    y: box.y + offset(y, box.height - height * scale),
    width: width * scale,
    height: height * scale,
  };
}
export function applyPhotoFilter(pixels: Uint8ClampedArray, grayscale: boolean, contrast: number) {
  for (let i = 0; i < pixels.length; i += 4) {
    const gray = 0.2126 * pixels[i] + 0.7152 * pixels[i + 1] + 0.0722 * pixels[i + 2];
    for (let c = 0; c < 3; c++)
      pixels[i + c] = ((grayscale ? gray : pixels[i + c]) - 127.5) * contrast + 127.5;
  }
}
