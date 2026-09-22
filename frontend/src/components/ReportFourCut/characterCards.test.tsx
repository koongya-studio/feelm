/*
 * 04 캐릭터 칸 — 코드로 저장소 그림을 찾는지 (업무 S15P21E106-780)
 *
 * 카드가 고정 16종이라 이 칸만 계약이 사진을 주지 않고 화면이 `characterCard.code`로
 * 짝짓는다(업무 -618). 그림 16장은 업무 -781이 넣으므로 **지금은 표가 비어 있다.** 표가
 * 비어도 칸이 동작하는지, 그리고 파일이 들어오면 붙는지를 둘 다 본다.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  CHARACTER_AXIS_POLES,
  CHARACTER_CARD_CODES,
  characterCardImage,
  isCharacterCardCode,
} from './characterCards';
import type { ReportFourCut as ReportFourCutData } from '../../types/report';

const cuts: ReportFourCutData = {
  genre: null,
  director: null,
  actor: null,
  character: { name: '안락의자 탐정' },
};

async function renderFourCut(code?: string | null) {
  const { default: ReportFourCut } = await import('.');
  render(<ReportFourCut cuts={cuts} createdAt="2026-09-01T12:00:00Z" characterCode={code} />);
  const element = document.querySelector('[data-cut="character"]');
  if (!(element instanceof HTMLElement)) throw new Error('캐릭터 칸이 없다');
  return element;
}

describe('캐릭터 카드 기준정보', () => {
  it('16종이고 코드가 겹치지 않는다', () => {
    expect(CHARACTER_CARD_CODES).toHaveLength(16);
    expect(new Set(CHARACTER_CARD_CODES).size).toBe(16);
  });

  it('네 축의 글자를 한 번씩 쓴 조합이 16종을 이룬다', () => {
    // 축 순서는 탐험·정착 -> 빛·그림자 -> 가슴·머리 -> 다작·정독이다(API-060)
    const axes = [
      CHARACTER_AXIS_POLES.EXPLORATION,
      CHARACTER_AXIS_POLES.TONE,
      CHARACTER_AXIS_POLES.STANCE,
      CHARACTER_AXIS_POLES.VOLUME,
    ];
    const built = axes.reduce<string[]>(
      (codes, poles) => codes.flatMap((code) => poles.map((pole) => code + pole.value)),
      [''],
    );

    expect([...CHARACTER_CARD_CODES].sort()).toEqual(built.sort());
  });

  it('계약 밖의 코드는 걸러낸다', () => {
    expect(isCharacterCardCode('RDMV')).toBe(true);
    expect(isCharacterCardCode('XXXX')).toBe(false);
    expect(isCharacterCardCode(null)).toBe(false);
    expect(isCharacterCardCode(4)).toBe(false);
  });

  it('그림이 없는 코드와 모르는 코드는 모두 null이다', () => {
    // 표가 채워지기 전이라 16종 전부 null이다(업무 -781)
    CHARACTER_CARD_CODES.forEach((code) => expect(characterCardImage(code)).toBeNull());
    expect(characterCardImage('XXXX')).toBeNull();
    expect(characterCardImage(null)).toBeNull();
    expect(characterCardImage(undefined)).toBeNull();
  });
});

describe('04 캐릭터 칸', () => {
  it('그림이 없으면 칸을 남기고 이름만 그린다', async () => {
    const character = await renderFourCut('RDMV');

    expect(character).toHaveAttribute('data-mode', 'none');
    expect(character.querySelector('img')).toBeNull();
    expect(screen.getByText('04 캐릭터')).toBeInTheDocument();
    expect(screen.getByText('안락의자 탐정')).toBeInTheDocument();
  });

  it('코드가 없어도, 계약 밖의 코드가 와도 칸이 깨지지 않는다', async () => {
    const character = await renderFourCut(null);

    expect(character).toHaveAttribute('data-mode', 'none');
    expect(screen.getByText('안락의자 탐정')).toBeInTheDocument();
  });

  it('그림이 들어오면 그 코드의 파일을 붙인다', async () => {
    // 업무 -781이 표를 채웠을 때의 모양. 코드로 찾은 파일 하나만 붙는다
    vi.resetModules();
    vi.doMock('./characterCards', async () => ({
      ...(await vi.importActual<typeof import('./characterCards')>('./characterCards')),
      characterCardImage: (code: string | null | undefined) =>
        code === 'RDMV' ? '/character/RDMV.webp' : null,
    }));

    const character = await renderFourCut('RDMV');

    expect(character).toHaveAttribute('data-mode', 'photo');
    expect(character.querySelector('img')).toHaveAttribute('src', '/character/RDMV.webp');

    vi.doUnmock('./characterCards');
    vi.resetModules();
  });
});
