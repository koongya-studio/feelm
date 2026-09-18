/*
 * 프로필 사진 고르기 · 자르기 (업무 S15P21E106-616)
 *
 * 7.2에서 고른 사진은 올리기 전에 **원 안에 들어갈 정사각형으로 자른다.** 원은 CSS가
 * 그리므로(`Avatar`) 저장하는 것은 정사각형 한 장이다. 사진 올리기 계약이 없어 결과는
 * data URL로 목업 저장소에 들어간다(`api/users.ts` `updateProfileExtras`).
 */

/** 받는 형식. HEIC 같은 형식은 브라우저가 그리지 못해 자를 수 없다 */
export const AVATAR_ACCEPT = ['image/jpeg', 'image/png', 'image/webp'] as const;

/** 올릴 수 있는 원본 크기 상한 */
export const AVATAR_MAX_BYTES = 10 * 1024 * 1024;

/** 잘라서 저장하는 한 변(px). 7.1의 가장 큰 자리(96px)의 3배 해상도를 넉넉히 덮는다 */
export const AVATAR_OUTPUT_SIZE = 512;

/** 고른 파일을 쓸 수 없는 이유. 쓸 수 있으면 `null`이다 */
export function avatarFileProblem(file: File): string | null {
  if (!(AVATAR_ACCEPT as readonly string[]).includes(file.type)) {
    return 'JPG, PNG, WEBP 사진만 올릴 수 있어요';
  }

  if (file.size > AVATAR_MAX_BYTES) {
    return '10MB 이하의 사진만 올릴 수 있어요';
  }

  return null;
}

/** 파일을 data URL로 읽는다. 자르기 화면이 이 주소로 사진을 그린다 */
export function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();

    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('사진을 읽지 못했어요'));
    reader.readAsDataURL(file);
  });
}

/**
 * 자르기 상태. 자르기 화면이 사진을 어떻게 놓았는지다.
 *
 * - `zoom` 1이면 사진이 원을 겨우 덮는 크기다. 그보다 작아지지 않는다
 * - `offsetX` · `offsetY` 사진 가운데가 원 가운데에서 떨어진 거리(화면 px)
 */
export type CropState = { zoom: number; offsetX: number; offsetY: number };

/** 원을 덮는 최소 배율. 사진의 짧은 변이 원 지름과 같아진다 */
export function coverScale(width: number, height: number, stage: number): number {
  return Math.max(stage / width, stage / height);
}

/** 사진이 원 밖으로 비지 않도록 움직일 수 있는 거리를 제한한다 */
export function clampCrop(
  crop: CropState,
  width: number,
  height: number,
  stage: number,
): CropState {
  const scale = coverScale(width, height, stage) * crop.zoom;
  const maxX = Math.max(0, (width * scale - stage) / 2);
  const maxY = Math.max(0, (height * scale - stage) / 2);

  return {
    zoom: crop.zoom,
    offsetX: Math.min(maxX, Math.max(-maxX, crop.offsetX)),
    offsetY: Math.min(maxY, Math.max(-maxY, crop.offsetY)),
  };
}

/**
 * 원 안에 보이는 부분을 원본 사진 좌표로 바꾼다. `drawImage`의 원본 사각형이다.
 *
 * 원 왼쪽 위는 사진 왼쪽 위에서 `(사진 폭 - 원) / 2 - offset`만큼 떨어져 있다(화면 px).
 * 배율로 나누면 원본 px이 된다.
 */
export function cropSourceRect(
  crop: CropState,
  width: number,
  height: number,
  stage: number,
): { x: number; y: number; size: number } {
  const scale = coverScale(width, height, stage) * crop.zoom;

  return {
    x: ((width * scale - stage) / 2 - crop.offsetX) / scale,
    y: ((height * scale - stage) / 2 - crop.offsetY) / scale,
    size: stage / scale,
  };
}

/** 잘라서 정사각형 JPEG data URL로 만든다. 그릴 수 없는 환경이면 실패한다 */
export function renderCrop(image: HTMLImageElement, crop: CropState, stage: number): string {
  const canvas = document.createElement('canvas');
  canvas.width = AVATAR_OUTPUT_SIZE;
  canvas.height = AVATAR_OUTPUT_SIZE;

  const context = canvas.getContext('2d');

  if (!context) throw new Error('사진을 자르지 못했어요');

  const source = cropSourceRect(crop, image.naturalWidth, image.naturalHeight, stage);

  context.drawImage(
    image,
    source.x,
    source.y,
    source.size,
    source.size,
    0,
    0,
    AVATAR_OUTPUT_SIZE,
    AVATAR_OUTPUT_SIZE,
  );

  return canvas.toDataURL('image/jpeg', 0.9);
}
