/*
 * 맛 코드 -> 3D 팝콘 스프라이트.
 *
 * 화면은 맛의 표시명과 색을 API-044에서 받지만 그림 파일은 저장소에 있는 것이라
 * 코드로 짝지어야 한다. 코드는 불변이므로(FR-06) 이 대응은 이름이 바뀌어도 살아남는다.
 * 모르는 코드가 오면 기본 맛으로 그린다.
 *
 * S15P21E106-466에서 439 taste 순서대로 FLAVOR_A~H를 기존 3D 자산 8종에 연결했다.
 *
 * 3.2 팝콘 통(`PopcornBucket`)이 갖고 있던 것을 3.1 요약도 쓰게 되면서 꺼냈다
 * (업무 -458). **색 면이 아니라 스프라이트로 맛을 보여준다** — 팝콘 8색은 밝은 면에서
 * 4종이 판별되지 않아(`design-concept.md` 2.5) 밝은 벽 위 요약에는 색 점을 쓸 수 없다.
 */
import blueberry from '../../assets/popcorn/popcorn-blueberry.png';
import caramel from '../../assets/popcorn/popcorn-caramel.png';
import cheese from '../../assets/popcorn/popcorn-cheese.png';
import chocolate from '../../assets/popcorn/popcorn-chocolate.png';
import cookie from '../../assets/popcorn/popcorn-cookie.png';
import original from '../../assets/popcorn/popcorn-original.png';
import pistachio from '../../assets/popcorn/popcorn-pistachio.png';
import strawberry from '../../assets/popcorn/popcorn-strawberry.png';

const KERNEL_SPRITE: Record<string, string> = {
  FLAVOR_A: original,
  FLAVOR_B: chocolate,
  FLAVOR_C: caramel,
  FLAVOR_D: pistachio,
  FLAVOR_E: cookie,
  FLAVOR_F: cheese,
  FLAVOR_G: strawberry,
  FLAVOR_H: blueberry,
};

/** 맛 코드의 팝콘 그림. 모르는 코드는 기본 맛으로 그린다 */
export function kernelSprite(flavorCode: string): string {
  return KERNEL_SPRITE[flavorCode] ?? original;
}
