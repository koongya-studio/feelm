/*
 * 팝콘 통 — 분기 테스트 (업무 S15P21E106-333)
 *
 * 이 컴포넌트에는 계약에 없는 판단이 셋 들어 있다.
 *
 * - 모르는 맛 코드가 오면 기본 맛으로 그린다(`KERNEL_SPRITE[code] ?? original`).
 *   API-044가 맛을 늘리면 저장소에 없는 코드가 올 수 있다
 * - 알이 0개여도 통은 그린다
 * - 자리와 방향이 index에서만 나온다. 난수를 쓰지 않으므로 **다시 그려도 더미가
 *   흔들리지 않아야 한다.** 난수로 바뀌면 화면이 매 렌더마다 튀는데, 눈으로는
 *   알아채기 어렵다
 *
 * `pileKernels`는 모듈 밖으로 노출되지 않으므로 렌더 결과의 SVG를 읽어 확인한다.
 * 순수 함수로 빼면 더 단단하지만 구현을 바꾸게 되므로, 첫 테스트에서는 구현을
 * 건드리지 않는 쪽을 골랐다.
 */
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import PopcornBucket from './index';

/** 통 스프라이트 하나 + 알 하나당 하나씩 `<image>`가 나온다 */
function images(container: HTMLElement) {
  return [...container.querySelectorAll('image')];
}

function kernelTransforms(container: HTMLElement) {
  return images(container)
    .map((el) => el.getAttribute('transform'))
    .filter((value): value is string => value !== null);
}

describe('PopcornBucket', () => {
  it('알이 없으면 통만 그린다', () => {
    const { container } = render(<PopcornBucket kernelFlavors={[]} label="빈 통" />);

    // 통 스프라이트 하나뿐이고 알은 없다
    expect(images(container)).toHaveLength(1);
    expect(kernelTransforms(container)).toHaveLength(0);
    expect(container.querySelector('[data-testid="popcorn-bucket"]')).toBeInTheDocument();
  });

  it('알 수만큼 그린다', () => {
    const codes = Array.from({ length: 12 }, () => 'FLAVOR_A');
    const { container } = render(<PopcornBucket kernelFlavors={codes} label="통" />);

    // 통 1 + 알 12
    expect(images(container)).toHaveLength(13);
  });

  it('모르는 맛 코드가 와도 터지지 않고 기본 맛 그림으로 그린다', () => {
    const known = render(<PopcornBucket kernelFlavors={['FLAVOR_A']} label="아는 맛" />);
    const knownHref = images(known.container)[1]?.getAttribute('href');

    const unknown = render(
      <PopcornBucket kernelFlavors={['FLAVOR_NOT_IN_REPO']} label="모르는 맛" />,
    );
    const unknownHref = images(unknown.container)[1]?.getAttribute('href');

    expect(unknownHref).toBeTruthy();
    // FLAVOR_A가 기본 맛(original)에 짝지어져 있으므로 같은 그림이어야 한다
    expect(unknownHref).toBe(knownHref);
  });

  it('439의 FLAVOR_A부터 FLAVOR_H까지 서로 다른 3D 알을 사용한다', () => {
    const codes = Array.from(
      { length: 8 },
      (_, index) => `FLAVOR_${String.fromCharCode(65 + index)}`,
    );
    const { container } = render(<PopcornBucket kernelFlavors={codes} label="맛 8종" />);
    const kernelHrefs = images(container)
      .slice(1)
      .map((image) => image.getAttribute('href'));

    expect(new Set(kernelHrefs).size).toBe(8);
  });

  it('같은 입력을 다시 그려도 알 자리가 흔들리지 않는다', () => {
    const codes = ['FLAVOR_A', 'FLAVOR_B', 'FLAVOR_C', 'FLAVOR_D'];

    const first = render(<PopcornBucket kernelFlavors={codes} label="첫 렌더" />);
    const second = render(<PopcornBucket kernelFlavors={codes} label="두 번째 렌더" />);

    const a = kernelTransforms(first.container);
    const b = kernelTransforms(second.container);

    expect(a).toHaveLength(codes.length);
    expect(b).toEqual(a);
  });

  it('폭을 주면 높이가 좌표계 비율을 따라간다', () => {
    const { container } = render(
      <PopcornBucket kernelFlavors={[]} width={100} label="비율 확인" />,
    );
    const svg = container.querySelector('svg');

    // 좌표계가 200x230이므로 폭 100이면 높이 115다
    expect(svg).toHaveAttribute('width', '100');
    expect(svg).toHaveAttribute('height', '115');
  });

  it('스크린 리더가 읽을 이름을 붙인다', () => {
    const { container } = render(<PopcornBucket kernelFlavors={[]} label="팝콘 100알" />);
    const svg = container.querySelector('svg');

    expect(svg).toHaveAttribute('role', 'img');
    expect(svg).toHaveAttribute('aria-label', '팝콘 100알');
  });
});
