/*
 * 팝콘 통.
 *
 * 취향을 한눈에 보여주는 자리다(3.2). 통과 알 모두 디자인이 만든 3D 모델을 구운
 * 스프라이트다.
 *
 * - 통: `src/assets/bucket`. **아이스 화이트 몸통 · 세로 필름 무늬 · 빨간 로고**다(사용자 결정
 *   2026-09-15, 업무 -322). 디자인의 블랙 · 아이스 화이트 두 통을 3.2 · 5.8 · 5.9 · 7.2-A에
 *   넣어 비교해 골랐다. 이전 크림 몸통 · 빨강/검정 통(2026-09-10)을 바꿨다.
 *   **빈 통**이다. 알을 따로 쌓아야 취향 분포가 그림에 반영되기 때문이다.
 *   렌더 조건 — 디자인 3D 뷰어(`Feelm 팝콘 버킷 디자인`)에서 바닥 그림자를 끄고 투명 배경으로
 *   384 × 384px에 구웠다. 원근 카메라 FOV 45°, 앞에서 15° 돌리고 24° 내려다본다. 통이 폭
 *   x 11~372에 꽉 차도록 거리를 맞췄다. 조명은 뷰어 기본(반구광 1 · 방향광 3)이다
 * - 알: `src/assets/popcorn`. **디자인의 팝콘 8종 라인업**(`Feelm 팝콘 8종 라인업`, 2026-09-15, 업무 -322)을
 *   한 알씩 192 × 192px 투명 배경으로 구웠다. 8종이 같은 모양 · 같은 방향이고 재질만 다르다.
 *   라인업의 이름표는 빼고 팝콘만 담았다. 렌더 조건은 통과 같다 — 원근 FOV 45°, 앞에서 15° 돌리고
 *   24° 내려다본다. 알이 긴 변의 88%를 차지하게 가운데에 맞췄다
 *
 * 알은 맛 분포대로 통 입구에 쌓인다. **알 더미는 그림이고 데이터를 읽는 채널이
 * 아니다.** 알 수와 맛 이름은 범례가 말한다.
 *
 * 자리와 방향은 index로만 정해진다. 난수를 쓰지 않으므로 다시 그려도 더미가 흔들리지
 * 않는다. 한 맛의 알이 모두 같은 그림이라 index로 좌우를 뒤집고 조금씩 돌려 변화를 준다.
 *
 * 통 스프라이트가 한 장이라 알을 통 앞벽 뒤로 숨길 수 없다. 그래서 더미를 입구 안에서
 * 시작해 위로 쌓는다. 넘칠 듯 담긴 모양이 되고, 앞쪽 테두리에 걸치는 알은 입구에 얹힌
 * 것처럼 읽힌다.
 */
import bucketSprite from '../../assets/bucket/bucket-film-vertical-icewhite.png';
import { kernelSprite } from './sprites';
import styles from './PopcornBucket.module.css';

/* 좌표계. 통 하나가 들어가는 상자다 */
const VIEW_WIDTH = 200;
const VIEW_HEIGHT = 230;

/*
 * 통 크기. 알에 비해 통이 커서 팝콘이 자잘한 알갱이로 보였다(사용자 요청 2026-09-15, 업무 -322).
 * 알 크기는 그대로 두고 **통만 이 비율로 줄인다.** 통 바닥(y 205)과 가로 가운데를 고정하고 줄이므로
 * 입구 · 더미의 자리도 같은 비율로 따라온다.
 */
const BUCKET_SCALE = 0.8;
/** 줄일 때 고정하는 점. 통 가로 가운데와 바닥 */
const ANCHOR_X = 100;
const ANCHOR_Y = 205;
const scaleX = (x: number) => ANCHOR_X - (ANCHOR_X - x) * BUCKET_SCALE;
const scaleY = (y: number) => ANCHOR_Y - (ANCHOR_Y - y) * BUCKET_SCALE;

/*
 * 통 스프라이트를 놓는 자리.
 *
 * 384px 원본에서 통은 y 41~341을 차지하고, 가장 넓은 줄(= 테두리 타원의 세로 중심)이
 * y 70에서 x 11~372다. 줄이기 전 값은 그 통이 이 좌표계의 폭을 꽉 채우도록 맞춘 것이다
 * (x −6, y 17, 한 변 212 — 통 바닥이 y 205).
 */
const SPRITE_X = scaleX(-6);
const SPRITE_Y = scaleY(17);
const SPRITE_SIZE = 212 * BUCKET_SCALE;

/*
 * 테두리 타원. 알을 이 안에 담는다.
 *
 * 384px 원본에서 통의 가장 넓은 줄이 y 70, x 11~372다. 줄이기 전 좌표계로 옮기면(× 212 / 384)
 * 중심이 (100, 56), 가로 반지름이 약 100이다. 타원의 세로 반지름은 통 꼭대기(y 41)까지의
 * 거리 29px를 옮긴 16이다. 아래 값은 여기에 통 크기를 적용한 것이다.
 */
const RIM_X = scaleX(100);
const RIM_Y = scaleY(56);
/** 입구 타원의 실제 크기. 알을 가두는 클립이 이 타원이다 */
const RIM_RX = 98 * BUCKET_SCALE;
const RIM_RY = 16 * BUCKET_SCALE;

/**
 * 알이 퍼지는 범위. 입구보다 좁게 잡아 통 벽에 붙지 않게 한다.
 *
 * 원본 렌더를 보면 팝콘이 통 안쪽까지 담겨 있고 앞쪽 테두리가 아래 알을 가린다.
 * 그 가림을 `clipPath`가 만든다. 클립은 입구 타원과 그 위쪽을 합친 모양이라, 테두리
 * 아래로 내려간 알은 타원 밖에서 잘려 통 뒤로 들어간 것처럼 보인다.
 */
const PILE_RX = 82 * BUCKET_SCALE;

/**
 * 더미가 테두리 위로 솟는 높이.
 *
 * 알을 평면에 늘어놓으면 통 위에 얹힌 판처럼 보인다. 입구를 밑면으로 하는 반구 안에
 * 알을 뿌려 실제로 담긴 더미가 되게 한다.
 */
const MOUND_HEIGHT = 46;

/** 더미를 통 안으로 내려 담는 깊이. 팝콘이 입구에 걸친 것처럼 보이게 한다 */
const PILE_SINK = 10 * BUCKET_SCALE;

/** 입구 클립의 id. 한 화면에 통이 하나라 고정 문자열로 충분하다 */
const CLIP_ID = 'bucket-mouth';

/** 알 한 알이 차지하는 정사각형 한 변. 새 스프라이트는 알이 폭의 88%라(이전 92%) 한 칸 키웠다 */
const KERNEL_SIZE = 31;
/** 앞뒤 거리에 따른 크기 차이. 앞이 크고 뒤가 작아야 더미에 깊이가 생긴다 */
const DEPTH_SCALE = 0.22;

/**
 * index만으로 0~1 값을 만든다. 같은 index는 항상 같은 값이라 더미가 고정된다.
 * 난수 생성기를 쓰지 않는 이유는 다시 그릴 때마다 알이 튀지 않게 하려는 것이다.
 */
function noise(index: number, salt: number): number {
  const value = Math.sin(index * 127.1 + salt * 311.7) * 43758.5453;

  return value - Math.floor(value);
}

type Kernel = {
  x: number;
  y: number;
  size: number;
  sprite: string;
  angle: number;
  flip: number;
  /** 앞뒤 자리(-1~1). 그리는 순서를 정할 뿐 화면에 직접 쓰이지는 않는다 */
  depth: number;
};

/**
 * 알을 통 입구 위 반구 안에 뿌린다.
 *
 * 입구를 원판으로 보고 그 위 반구를 채운다. `dx`·`dz`는 원판 위의 자리이고 `height`는
 * 그 자리에서 반구가 허용하는 높이다. 가장자리로 갈수록 높이가 0에 가까워지므로 더미가
 * 테두리에서 자연스럽게 낮아진다.
 *
 * `dz`는 앞뒤다. 화면에서는 테두리 타원을 따라 아래위로 옮기고, 알 크기도 같이 키우고
 * 줄여 원근을 만든다. 뒤쪽 알부터 그려서 앞쪽 알이 그 위를 덮는다.
 */
function pileKernels(flavorCodes: string[]): Kernel[] {
  return flavorCodes
    .map((code, index) => {
      // 원판 위 균등 분포. sqrt를 쓰지 않으면 알이 가운데로 몰린다
      const radius = Math.sqrt(noise(index, 1));
      const theta = noise(index, 2) * Math.PI * 2;
      const dx = radius * Math.cos(theta);
      const dz = radius * Math.sin(theta);

      /*
       * 더미는 위로 갈수록 좁아진다. 원본 렌더의 팝콘도 반구가 아니라 봉우리에 가깝다.
       * 지수를 1.6으로 두면 가운데만 솟고 가장자리는 완만해진다.
       *
       * 껍질 근처(높이의 70~100%)로 모은다. 부피에 고르게 뿌리면 알 사이가 벌어져
       * 가운데로 빈 통 안쪽이 비친다.
       */
      const peak = MOUND_HEIGHT * (1 - radius ** 1.6);
      const height = peak * (0.7 + noise(index, 3) * 0.3);

      return {
        x: RIM_X + dx * PILE_RX,
        // PILE_SINK만큼 통 안으로 내려 담는다. 입구 아래로 간 알은 클립이 잘라 준다
        y: RIM_Y + PILE_SINK + dz * RIM_RY - height,
        size: KERNEL_SIZE * (1 + dz * DEPTH_SCALE),
        sprite: kernelSprite(code),
        angle: (noise(index, 4) * 2 - 1) * 30,
        flip: noise(index, 5) < 0.5 ? -1 : 1,
        depth: dz,
      };
    })
    .sort((a, b) => a.depth - b.depth);
}

type Props = {
  /** 통 위에 쌓을 알의 맛 코드. 순서는 상관없다 */
  kernelFlavors?: string[];
  /** 그려지는 폭(px). 높이는 비율로 따라간다 */
  width?: number;
  /** 스크린 리더가 읽을 이름 */
  label: string;
};

export default function PopcornBucket({ kernelFlavors = [], width = 232, label }: Props) {
  const kernels = pileKernels(kernelFlavors);

  return (
    <svg
      className={styles.bucket}
      width={width}
      height={(width * VIEW_HEIGHT) / VIEW_WIDTH}
      viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
      role="img"
      aria-label={label}
      data-testid="popcorn-bucket"
    >
      <defs>
        {/*
         * 입구 타원 + 그 위쪽을 합친 모양. 알은 이 안에서만 보인다.
         *
         * 통 스프라이트가 한 장이라 알을 앞벽 뒤로 숨길 수 없다. 대신 입구 밖을
         * 잘라 테두리 아래로 내려간 알이 통 속으로 들어간 것처럼 보이게 한다.
         */}
        <clipPath id={CLIP_ID}>
          <ellipse cx={RIM_X} cy={RIM_Y} rx={RIM_RX} ry={RIM_RY} />
          <rect x={RIM_X - RIM_RX} y={0} width={RIM_RX * 2} height={RIM_Y} />
        </clipPath>
      </defs>

      <image
        href={bucketSprite}
        x={SPRITE_X}
        y={SPRITE_Y}
        width={SPRITE_SIZE}
        height={SPRITE_SIZE}
      />

      <g clipPath={`url(#${CLIP_ID})`}>
        {kernels.map((kernel, index) => (
          <image
            key={index}
            href={kernel.sprite}
            width={kernel.size}
            height={kernel.size}
            x={-kernel.size / 2}
            y={-kernel.size / 2}
            transform={`translate(${kernel.x} ${kernel.y}) rotate(${kernel.angle}) scale(${kernel.flip} 1)`}
          />
        ))}
      </g>
    </svg>
  );
}
