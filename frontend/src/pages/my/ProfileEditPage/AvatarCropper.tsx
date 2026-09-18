/*
 * 프로필 사진 자르기 (업무 S15P21E106-616)
 *
 * 고른 사진을 원 안에 맞춘다. 끌어서 위치를, 막대로 크기를 조정한다. 키보드로도 된다 —
 * 원에 초점을 두고 화살표로 옮기고, 막대는 좌우 화살표다.
 *
 * 사진은 원을 늘 덮는다. 원보다 작게 줄이거나 원 밖으로 비게 옮길 수 없다(`clampCrop`).
 * `적용`을 누르면 원 안에 보이는 그대로를 정사각형으로 잘라 넘긴다(`renderCrop`).
 */
import { useRef, useState } from 'react';
import type { KeyboardEvent, PointerEvent } from 'react';
import Button from '../../../components/Button';
import { clampCrop, coverScale, renderCrop } from '../../../utils/avatarImage';
import type { CropState } from '../../../utils/avatarImage';
import styles from './ProfileEditPage.module.css';

/** 원 지름(px). 320px 화면에서도 좌우 여백 안에 든다 */
const STAGE = 256;
const MAX_ZOOM = 3;
/** 화살표 한 번에 옮기는 거리 */
const KEY_STEP = 8;

type Props = {
  src: string;
  onApply: (dataUrl: string) => void;
  onCancel: () => void;
};

export default function AvatarCropper({ src, onApply, onCancel }: Props) {
  const imageRef = useRef<HTMLImageElement>(null);
  const drag = useRef<{ x: number; y: number; crop: CropState } | null>(null);

  const [natural, setNatural] = useState<{ width: number; height: number } | null>(null);
  const [crop, setCrop] = useState<CropState>({ zoom: 1, offsetX: 0, offsetY: 0 });
  const [error, setError] = useState('');

  const update = (next: CropState) => {
    if (!natural) return;
    setCrop(clampCrop(next, natural.width, natural.height, STAGE));
  };

  const scale = natural ? coverScale(natural.width, natural.height, STAGE) * crop.zoom : 1;
  const width = natural ? natural.width * scale : STAGE;
  const height = natural ? natural.height * scale : STAGE;

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { x: event.clientX, y: event.clientY, crop };
  };

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const start = drag.current;

    if (!start) return;

    update({
      zoom: start.crop.zoom,
      offsetX: start.crop.offsetX + event.clientX - start.x,
      offsetY: start.crop.offsetY + event.clientY - start.y,
    });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const move: Record<string, [number, number]> = {
      ArrowLeft: [-KEY_STEP, 0],
      ArrowRight: [KEY_STEP, 0],
      ArrowUp: [0, -KEY_STEP],
      ArrowDown: [0, KEY_STEP],
    };
    const delta = move[event.key];

    if (!delta) return;

    event.preventDefault();
    update({ ...crop, offsetX: crop.offsetX + delta[0], offsetY: crop.offsetY + delta[1] });
  };

  const apply = () => {
    if (!imageRef.current || !natural) return;

    try {
      onApply(renderCrop(imageRef.current, crop, STAGE));
    } catch {
      setError('사진을 자르지 못했어요. 다른 사진을 골라 주세요');
    }
  };

  return (
    <div className={styles.cropLayer} role="dialog" aria-modal="true" aria-label="사진 위치 조정">
      <p className={styles.cropTitle}>사진 위치 조정</p>
      <p className={styles.cropHint}>끌어서 옮기고, 아래 막대로 크기를 조정해요</p>

      <div
        className={styles.cropStage}
        style={{ width: STAGE, height: STAGE }}
        tabIndex={0}
        role="group"
        aria-label="사진 위치. 화살표 키로 옮겨요"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={() => {
          drag.current = null;
        }}
        onPointerCancel={() => {
          drag.current = null;
        }}
        onKeyDown={onKeyDown}
      >
        <img
          ref={imageRef}
          className={styles.cropImage}
          src={src}
          alt=""
          draggable={false}
          style={{
            width,
            height,
            left: STAGE / 2 - width / 2 + crop.offsetX,
            top: STAGE / 2 - height / 2 + crop.offsetY,
          }}
          onLoad={(event) =>
            setNatural({
              width: event.currentTarget.naturalWidth,
              height: event.currentTarget.naturalHeight,
            })
          }
          onError={() => setError('사진을 열지 못했어요. 다른 사진을 골라 주세요')}
        />
      </div>

      <label className={styles.cropZoom}>
        <span>크기</span>
        <input
          type="range"
          min={1}
          max={MAX_ZOOM}
          step={0.01}
          value={crop.zoom}
          disabled={!natural}
          onChange={(event) => update({ ...crop, zoom: Number(event.target.value) })}
        />
      </label>

      {error ? (
        <p className={styles.cropError} role="alert">
          {error}
        </p>
      ) : null}

      <div className={styles.cropActions}>
        {/* 어두운 면이라 공용 테두리 버튼은 글자가 묻힌다. 흰 테두리 · 흰 글자로 둔다 */}
        <button type="button" className={styles.cropCancel} onClick={onCancel}>
          취소
        </button>
        <Button variant="brand" disabled={!natural} onClick={apply}>
          적용
        </Button>
      </div>
    </div>
  );
}
