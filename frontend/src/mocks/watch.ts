/*
 * 평가 목업. API-036
 *
 * 여기에는 값을 만드는 함수만 둔다. 만들어진 평가는 mocks/sessionStore.ts에 쌓이고,
 * `src/api/`가 기준 목업과 합쳐 응답을 만든다(업무 -262).
 *
 * 감상 기록 목업은 없어졌다. API-033 ~ API-035가 실제 호출이 되면서 기록을 서버가
 * 들고 있기 때문이다(업무 -477).
 *
 * 프레임 수와 팝콘 알 수는 더 이상 여기 고정값으로 두지 않는다. 기준값은
 * mocks/user.ts · mocks/frames.ts · mocks/popcornBucket.ts의 100이고, 현재 수치는
 * 거기에 이번 세션의 평가를 더해 계산한다.
 */
import type { Rating } from '../types/watch';
import type { Visibility } from '../types/enums';

/**
 * 이번 주에 추가된 프레임 수의 기준값. **계약에 대응 필드가 없다**
 * (types/unspecified.ts의 `weeklyFrameDelta`). 와이어프레임 2.6의 `이번 주 +3`이다.
 */
export const BASE_WEEKLY_FRAME_DELTA = 3;

let nextRatingId = 91;
let nextFrameId = 77;

export function createMockRating(
  movieId: number,
  score: number,
  comment: string | null,
  visibility: Visibility,
  flavorCode: string,
): Rating {
  const now = new Date().toISOString();

  nextRatingId += 1;
  nextFrameId += 1;

  return {
    ratingId: nextRatingId,
    movieId,
    score,
    comment,
    visibility,
    frame: { frameId: nextFrameId, createdAt: now },
    // 평가 하나가 프레임 1개와 팝콘 1알을 만든다(FR-07, FR-09)
    popcorn: { flavorCode, isNewFlavor: false, flavorCount: 1 },
    watchStatus: 'RATED',
    createdAt: now,
  };
}
