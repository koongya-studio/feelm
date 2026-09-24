import { beforeEach, describe, expect, it } from 'vitest';
import {
  FIRST_USE_GUIDE,
  RECOMMENDATIONS_GUIDE,
  completeGuide,
  hasSeenGuide,
  readFirstUseStep,
  restartFirstUse,
  restartGuide,
  setFirstUseStep,
} from './userGuides';

describe('사용 안내 상태', () => {
  beforeEach(() => localStorage.clear());

  it('첫 필름 완료와 추천 화면 안내를 계정별로 구분한다', () => {
    setFirstUseStep(1, 'done');
    completeGuide(1, RECOMMENDATIONS_GUIDE);

    expect(hasSeenGuide(1, FIRST_USE_GUIDE)).toBe(true);
    expect(readFirstUseStep(2)).toBeNull();

    restartFirstUse(1);
    expect(readFirstUseStep(1)).toBe('done');
    expect(hasSeenGuide(1, RECOMMENDATIONS_GUIDE)).toBe(false);
    expect(readFirstUseStep(2)).toBeNull();
  });

  it('새 기능과 버전의 완료 상태를 독립적으로 보관한다', () => {
    completeGuide(1, 'feature-ott-v1');
    completeGuide(1, FIRST_USE_GUIDE);

    expect(hasSeenGuide(1, 'feature-ott-v1')).toBe(true);
    expect(hasSeenGuide(1, 'feature-ott-v2')).toBe(false);
    expect(hasSeenGuide(2, 'feature-ott-v1')).toBe(false);

    restartGuide(1, 'feature-ott-v1');
    expect(hasSeenGuide(1, 'feature-ott-v1')).toBe(false);
    expect(hasSeenGuide(1, FIRST_USE_GUIDE)).toBe(true);
  });
});
