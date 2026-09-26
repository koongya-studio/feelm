import { useState } from 'react';
import { useNavigate } from 'react-router';
import GuideCallout from '../../../components/GuideCallout';
import { path } from '../../../routes/paths';
import {
  MY_GUIDE,
  completeGuide,
  hasSeenGuide,
  readGuideProgress,
  setGuideProgress,
} from '../../../utils/userGuides';

/** 계정마다 첫 MY 방문을 안내한다. 로딩·실패 화면에서는 부모가 렌더하지 않는다. */
export default function MyGuide({ userId, desktop }: { userId: number; desktop: boolean }) {
  const navigate = useNavigate();
  const [dismissed, setDismissed] = useState(false);
  const [step, setStep] = useState(() =>
    readGuideProgress(userId, MY_GUIDE) === 'settings' ? 2 : 1,
  );
  if (dismissed || hasSeenGuide(userId, MY_GUIDE)) return null;

  function finish() {
    completeGuide(userId, MY_GUIDE);
    setDismissed(true);
  }

  return (
    <GuideCallout
      title={step === 1 ? '내 기록 활용' : '설정에서 관리해요'}
      description={
        step === 1
          ? '평가한 영화를 눌러 기록을 수정하고, 최근 본 영화에서 평가 안 한 영화를 찾아보세요. 추천 기록과 관심 없음으로 표시한 영화도 다시 볼 수 있어요.'
          : '설정에서 내 기록의 공개 범위를 바꾸고, 구독 OTT를 관리하거나 나에게 맞는 OTT를 추천받을 수 있어요.'
      }
      step={step}
      totalSteps={2}
      targetSelector={
        step === 1
          ? "[data-guide-target='my-records']"
          : desktop
            ? "nav[aria-label='주요 화면'] ~ div a[href$='/settings']"
            : "[data-guide-target='my-settings']"
      }
      placement={step === 1 ? 'top' : 'bottom'}
      onSkip={finish}
      action={
        step === 1
          ? {
              label: '다음',
              onClick: () => {
                setGuideProgress(userId, MY_GUIDE, 'settings');
                setStep(2);
              },
            }
          : {
              label: '설정 둘러보기',
              onClick: () => {
                finish();
                void navigate(path.settings());
              },
            }
      }
      secondaryAction={step === 2 ? { label: '나중에', onClick: finish } : undefined}
    />
  );
}
