/*
 * 팝콘 버킷(API-041)의 맛 분포를 통 위에 쌓을 알 목록으로 편다. **한 알이 영화 한 편이다.**
 *
 * 3.2 취향 팝콘이 쓰던 것을 7.1 MY의 `취향 팝콘` 바로가기가 같이 쓰면서 컴포넌트 옆으로
 * 옮겼다(업무 -459). 통을 그리는 쪽은 맛 코드 목록만 알면 되고, 순서는 상관없다
 * (`PopcornBucket`이 index로 자리를 정한다).
 */
import type { PopcornBucketFlavor } from '../../types/taste';

export function kernelFlavors(flavors: PopcornBucketFlavor[]): string[] {
  return flavors.flatMap((flavor) =>
    Array.from({ length: flavor.popcornCount }, () => flavor.flavorCode),
  );
}
