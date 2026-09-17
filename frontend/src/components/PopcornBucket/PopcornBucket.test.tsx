/*
 * 팝콘 통 — 분기 테스트 (업무 S15P21E106-333)
 *
 * 이 컴포넌트에는 계약에 없는 판단이 넷 들어 있다.
 *
 * - 모르는 맛 코드가 오면 기본 맛으로 그린다(`KERNEL_SPRITE[code] ?? original`).
 *   API-044가 맛을 늘리면 저장소에 없는 코드가 올 수 있다
 * - 알이 0개여도 통은 그린다
 * - 자리와 방향이 index에서만 나온다. 난수를 쓰지 않으므로 **다시 그려도 더미가
 *   흔들리지 않아야 한다.** 난수로 바뀌면 화면이 매 렌더마다 튀는데, 눈으로는
 *   알아채기 어렵다
 * - **맛별 알 수를 30알로 요약한다**(업무 -577). 통에 담긴 알 수는 이제 실제 팝콘 수가
 *   아니다. 요약이 맛 하나를 통째로 떨어뜨리면 취향이 그림에서 사라지므로, 한 알이라도
 *   있는 맛은 반드시 남아야 한다
 *
 * `pileKernels`는 모듈 밖으로 노출되지 않으므로 렌더 결과의 SVG를 읽어 확인한다.
 * 순수 함수로 빼면 더 단단하지만 구현을 바꾸게 되므로, 첫 테스트에서는 구현을
 * 건드리지 않는 쪽을 골랐다.
 */
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import PopcornBucket from './index';
import type { FlavorCount } from './kernels';

/** 화면에 보이는 알 수. 컴포넌트의 `SHOWN_COUNT`와 같은 값이다 */
const SHOWN_COUNT = 30;

/** 통 스프라이트 하나 + 알 하나당 하나씩 `<image>`가 나온다 */
function images(container: HTMLElement) {
  return [...container.querySelectorAll('image')];
}

function kernels(container: HTMLElement) {
  return images(container).slice(1);
}

function kernelTransforms(container: HTMLElement) {
  return images(container)
    .map((el) => el.getAttribute('transform'))
    .filter((value): value is string => value !== null);
}

/** 맛 코드 여덟 종을 같은 수로 담은 입력 */
function evenFlavors(count: number, each = 10): FlavorCount[] {
  return Array.from({ length: count }, (_, index) => ({
    code: `FLAVOR_${String.fromCharCode(65 + index)}`,
    count: each,
  }));
}

describe('PopcornBucket', () => {
  it('알이 없으면 통만 그린다', () => {
    const { container } = render(<PopcornBucket flavors={[]} label="빈 통" />);

    // 통 스프라이트 하나뿐이고 알은 없다
    expect(images(container)).toHaveLength(1);
    expect(kernelTransforms(container)).toHaveLength(0);
    expect(container.querySelector('[data-testid="popcorn-bucket"]')).toBeInTheDocument();
  });

  it('알 수가 0인 맛만 오면 통만 그린다', () => {
    const { container } = render(
      <PopcornBucket flavors={[{ code: 'FLAVOR_A', count: 0 }]} label="빈 통" />,
    );

    expect(images(container)).toHaveLength(1);
  });

  it('팝콘이 몇 알이든 보이는 알은 30알로 요약한다', () => {
    const few = render(<PopcornBucket flavors={[{ code: 'FLAVOR_A', count: 7 }]} label="적게" />);
    const many = render(
      <PopcornBucket flavors={[{ code: 'FLAVOR_A', count: 900 }]} label="많이" />,
    );

    expect(kernels(few.container)).toHaveLength(SHOWN_COUNT);
    expect(kernels(many.container)).toHaveLength(SHOWN_COUNT);
  });

  it('한 알뿐인 맛도 그림에서 사라지지 않는다', () => {
    const { container } = render(
      <PopcornBucket
        flavors={[
          { code: 'FLAVOR_A', count: 500 },
          { code: 'FLAVOR_B', count: 1 },
        ]}
        label="한 알"
      />,
    );
    const hrefs = kernels(container).map((image) => image.getAttribute('href'));

    expect(hrefs).toHaveLength(SHOWN_COUNT);
    expect(hrefs.some((href) => href?.includes('popcorn-chocolate.png'))).toBe(true);
  });

  it('맛 수가 보이는 알 수보다 많으면 맛마다 한 알씩 그린다', () => {
    const { container } = render(
      <PopcornBucket flavors={evenFlavors(SHOWN_COUNT + 5, 1)} label="많은 맛" />,
    );

    expect(kernels(container)).toHaveLength(SHOWN_COUNT + 5);
  });

  it('모르는 맛 코드가 와도 터지지 않고 기본 맛 그림으로 그린다', () => {
    const known = render(
      <PopcornBucket flavors={[{ code: 'FLAVOR_A', count: 1 }]} label="아는 맛" />,
    );
    const unknown = render(
      <PopcornBucket flavors={[{ code: 'FLAVOR_NOT_IN_REPO', count: 1 }]} label="모르는 맛" />,
    );

    const knownHref = images(known.container)[1]?.getAttribute('href');
    const unknownHref = images(unknown.container)[1]?.getAttribute('href');

    expect(unknownHref).toBeTruthy();
    // FLAVOR_A가 기본 맛(original)에 짝지어져 있으므로 같은 그림이어야 한다
    expect(unknownHref).toBe(knownHref);
  });

  it('439의 FLAVOR_A부터 FLAVOR_H까지 서로 다른 3D 알을 사용한다', () => {
    const { container } = render(<PopcornBucket flavors={evenFlavors(8)} label="맛 8종" />);
    const kernelHrefs = kernels(container).map((image) => image.getAttribute('href'));

    expect(new Set(kernelHrefs).size).toBe(8);
  });

  it('확정된 FLAVOR_A부터 FLAVOR_H까지 맛에 맞는 3D 알을 사용한다', () => {
    const expectedAssets = [
      'original',
      'chocolate',
      'caramel',
      'pistachio',
      'cookie',
      'cheese',
      'strawberry',
      'blueberry',
    ];

    expectedAssets.forEach((asset, index) => {
      const code = `FLAVOR_${String.fromCharCode(65 + index)}`;
      const { container } = render(<PopcornBucket flavors={[{ code, count: 1 }]} label={code} />);
      const href = images(container)[1]?.getAttribute('href');

      expect(href).toContain(`popcorn-${asset}.png`);
    });
  });

  it('같은 입력을 다시 그려도 알 자리가 흔들리지 않는다', () => {
    const flavors = evenFlavors(4);

    const first = render(<PopcornBucket flavors={flavors} label="첫 렌더" />);
    const second = render(<PopcornBucket flavors={flavors} label="두 번째 렌더" />);

    const a = kernelTransforms(first.container);
    const b = kernelTransforms(second.container);

    expect(a).toHaveLength(SHOWN_COUNT);
    expect(b).toEqual(a);
  });

  it('맛 순서가 달라도 같은 그림이 나온다', () => {
    const flavors: FlavorCount[] = [
      { code: 'FLAVOR_A', count: 30 },
      { code: 'FLAVOR_B', count: 10 },
    ];

    const asGiven = render(<PopcornBucket flavors={flavors} label="그대로" />);
    const reversed = render(<PopcornBucket flavors={[...flavors].reverse()} label="뒤집어서" />);

    expect(kernels(reversed.container).map((image) => image.getAttribute('href'))).toEqual(
      kernels(asGiven.container).map((image) => image.getAttribute('href')),
    );
  });

  it('가장 많은 맛이 앞 가운데에 온다', () => {
    const { container } = render(
      <PopcornBucket
        flavors={[
          { code: 'FLAVOR_C', count: 10 },
          { code: 'FLAVOR_A', count: 60 },
          { code: 'FLAVOR_B', count: 30 },
        ]}
        label="세 맛"
      />,
    );

    /*
     * 뒤에서 앞 순서로 그리므로 마지막에 그려지는 알이 가장 앞에 있는 알이다.
     * 앞줄이 가장 많은 맛으로 차 있어야 무엇을 제일 많이 봤는지가 그림에 보인다.
     */
    const front = kernels(container)
      .slice(-8)
      .map((image) => image.getAttribute('href'));

    expect(front.every((href) => href?.includes('popcorn-original.png'))).toBe(true);
  });

  it('폭을 주면 높이가 좌표계 비율을 따라간다', () => {
    const { container } = render(<PopcornBucket flavors={[]} width={100} label="비율 확인" />);
    const svg = container.querySelector('svg');

    // 좌표계가 200x230이므로 폭 100이면 높이 115다
    expect(svg).toHaveAttribute('width', '100');
    expect(svg).toHaveAttribute('height', '115');
  });

  it('스크린 리더가 읽을 이름을 붙인다', () => {
    const { container } = render(<PopcornBucket flavors={[]} label="팝콘 100알" />);
    const svg = container.querySelector('svg');

    expect(svg).toHaveAttribute('role', 'img');
    expect(svg).toHaveAttribute('aria-label', '팝콘 100알');
  });
});
