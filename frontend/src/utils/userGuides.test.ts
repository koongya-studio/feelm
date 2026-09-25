import { beforeEach, describe, expect, it } from 'vitest';
import {
  FIRST_USE_GUIDE,
  RECOMMENDATIONS_GUIDE,
  completeGuide,
  hasSeenGuide,
  readGuideProgress,
  readFirstUseStep,
  restartFirstUse,
  restartGuide,
  setGuideProgress,
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

  it('화면을 떠난 중간 단계를 기억하고 완료하거나 다시 보면 지운다', () => {
    setGuideProgress(1, 'film-overview-v2', 'rate');
    expect(readGuideProgress(1, 'film-overview-v2')).toBe('rate');
    expect(readGuideProgress(2, 'film-overview-v2')).toBeNull();

    completeGuide(1, 'film-overview-v2');
    expect(readGuideProgress(1, 'film-overview-v2')).toBeNull();

    setGuideProgress(1, 'film-overview-v2', 'recommendations');
    restartGuide(1, 'film-overview-v2');
    expect(readGuideProgress(1, 'film-overview-v2')).toBeNull();
  });
});
