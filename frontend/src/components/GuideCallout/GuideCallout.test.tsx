import { expect, it } from 'vitest';
import { render } from '@testing-library/react';
import GuideCallout from './index';
import styles from './GuideCallout.module.css';

it('다음 단계의 대상이 없으면 이전 단계의 강조 표시를 지운다', () => {
  const { rerender } = render(
    <>
      <button data-guide-target="first">첫 대상</button>
      <GuideCallout
        title="첫 단계"
        description="안내"
        step={1}
        targetSelector="[data-guide-target='first']"
      />
    </>,
  );

  expect(document.querySelector(`.${styles.focusRing}`)).toBeInTheDocument();

  rerender(
    <>
      <button data-guide-target="first">첫 대상</button>
      <GuideCallout
        title="다음 단계"
        description="안내"
        step={2}
        targetSelector="[data-guide-target='missing']"
      />
    </>,
  );

  expect(document.querySelector(`.${styles.focusRing}`)).not.toBeInTheDocument();
});
