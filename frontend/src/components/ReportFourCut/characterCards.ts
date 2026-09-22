/*
 * 캐릭터 카드 코드 -> 04 칸 그림.
 *
 * 화면은 카드의 이름 · 한 줄 · 설명문을 API-060에서 받지만 그림 파일은 저장소에 있는 것이라
 * 코드로 짝지어야 한다. 코드는 불변이므로(API-060, 업무 -618) 이 대응은 이름이 바뀌어도
 * 살아남는다. `PopcornBucket/sprites.ts`가 같은 이유로 이미 이 구조다.
 *
 * 카드가 16종으로 고정이라 04 칸만 이 방식이다. 01 장르 · 02 감독 · 03 배우는 실존 인물 ·
 * 실제 스틸이라 계약이 주는 사진을 쓴다(API-060 미정 사항).
 *
 * 그림 16장은 업무 -781에서 만들어 `src/assets/reports/character/<code>.webp`에 넣었다.
 * **계약 밖의 코드는 여전히 null이고 칸은 이름만 그린다** — 모르는 값이 와도 화면은 동작한다.
 */

import ELHV from '../../assets/reports/character/ELHV.webp';
import ELHS from '../../assets/reports/character/ELHS.webp';
import ELMV from '../../assets/reports/character/ELMV.webp';
import ELMS from '../../assets/reports/character/ELMS.webp';
import EDHV from '../../assets/reports/character/EDHV.webp';
import EDHS from '../../assets/reports/character/EDHS.webp';
import EDMV from '../../assets/reports/character/EDMV.webp';
import EDMS from '../../assets/reports/character/EDMS.webp';
import RLHV from '../../assets/reports/character/RLHV.webp';
import RLHS from '../../assets/reports/character/RLHS.webp';
import RLMV from '../../assets/reports/character/RLMV.webp';
import RLMS from '../../assets/reports/character/RLMS.webp';
import RDHV from '../../assets/reports/character/RDHV.webp';
import RDHS from '../../assets/reports/character/RDHS.webp';
import RDMV from '../../assets/reports/character/RDMV.webp';
import RDMS from '../../assets/reports/character/RDMS.webp';

/** 16종 전체. 축 순서는 탐험·정착 -> 빛·그림자 -> 가슴·머리 -> 다작·정독이다 */
export const CHARACTER_CARD_CODES = [
  'ELHV',
  'ELHS',
  'ELMV',
  'ELMS',
  'EDHV',
  'EDHS',
  'EDMV',
  'EDMS',
  'RLHV',
  'RLHS',
  'RLMV',
  'RLMS',
  'RDHV',
  'RDHS',
  'RDMV',
  'RDMS',
] as const;

export type CharacterCardCode = (typeof CHARACTER_CARD_CODES)[number];

/**
 * 코드별 그림. 16종이 모두 채워져 있다(업무 -781). 표에 없는 코드는 그림이 없는 것으로 다룬다.
 *
 * 순서는 `CHARACTER_CARD_CODES`와 같게 둔다 — 축 조합을 눈으로 따라가며 빠진 칸을 찾는다.
 */
const CARD_IMAGE: Record<CharacterCardCode, string> = {
  ELHV,
  ELHS,
  ELMV,
  ELMS,
  EDHV,
  EDHS,
  EDMV,
  EDMS,
  RLHV,
  RLHS,
  RLMV,
  RLMS,
  RDHV,
  RDHS,
  RDMV,
  RDMS,
};

/** 코드가 16종에 있는지. 계약 밖의 값이 와도 화면이 깨지지 않게 먼저 걸러낸다 */
export function isCharacterCardCode(code: unknown): code is CharacterCardCode {
  return typeof code === 'string' && (CHARACTER_CARD_CODES as readonly string[]).includes(code);
}

/** 카드 코드의 04 칸 그림. 계약 밖의 코드는 null이다 */
export function characterCardImage(code: string | null | undefined): string | null {
  return isCharacterCardCode(code) ? CARD_IMAGE[code] : null;
}

/**
 * 축의 두 끝. API-060 처리 규칙이 고정한 값이라 화면이 가질 수 있다.
 *
 * 계약은 **고른 쪽의 `label`만** 주므로 반대쪽 이름을 여기서 가져온다. 고른 쪽 이름은 계약이
 * 준 `label`을 그대로 쓴다 — 표시명이 바뀌면 서버 쪽이 먼저 바뀐다.
 */
export const CHARACTER_AXIS_POLES = {
  EXPLORATION: [
    { value: 'E', label: '탐험' },
    { value: 'R', label: '정착' },
  ],
  TONE: [
    { value: 'L', label: '빛' },
    { value: 'D', label: '그림자' },
  ],
  STANCE: [
    { value: 'H', label: '가슴' },
    { value: 'M', label: '머리' },
  ],
  VOLUME: [
    { value: 'V', label: '다작' },
    { value: 'S', label: '정독' },
  ],
} as const satisfies Record<
  string,
  readonly [{ value: string; label: string }, { value: string; label: string }]
>;
