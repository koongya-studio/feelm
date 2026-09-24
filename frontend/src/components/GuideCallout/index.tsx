import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import styles from './GuideCallout.module.css';

type Props = {
  title: string;
  description: string;
  step: number;
  totalSteps?: number;
  targetSelector?: string;
  placement?: 'top' | 'bottom';
  onSkip?: () => void;
  action?: { label: string; onClick: () => void };
  secondaryAction?: { label: string; onClick: () => void };
};

type Spotlight = { top: number; right: number; bottom: number; left: number };

/** 화면의 요소를 강조하며 단계별로 설명하는 튜토리얼. */
export default function GuideCallout({
  title,
  description,
  step,
  totalSteps = 3,
  targetSelector,
  placement = 'bottom',
  onSkip,
  action,
  secondaryAction,
}: Props) {
  const [spotlight, setSpotlight] = useState<Spotlight | null>(null);

  useEffect(() => {
    if (!targetSelector) return;
    const target = document.querySelector<HTMLElement>(targetSelector);
    if (!target) return;
    const update = () => {
      const rect = target.getBoundingClientRect();
      const inset = 8;
      setSpotlight({
        top: Math.max(0, rect.top - inset),
        right: Math.min(window.innerWidth, rect.right + inset),
        bottom: Math.min(window.innerHeight, rect.bottom + inset),
        left: Math.max(0, rect.left - inset),
      });
    };
    target.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
    update();
    window.addEventListener('resize', update);
    window.addEventListener('scroll', update, true);
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    observer?.observe(target);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', update);
      window.removeEventListener('scroll', update, true);
    };
  }, [targetSelector]);

  return createPortal(
    <>
      {spotlight ? (
        <>
          <div
            className={styles.shade}
            style={{ top: 0, left: 0, right: 0, height: spotlight.top }}
            aria-hidden="true"
          />
          <div
            className={styles.shade}
            style={{ top: spotlight.bottom, left: 0, right: 0, bottom: 0 }}
            aria-hidden="true"
          />
          <div
            className={styles.shade}
            style={{
              top: spotlight.top,
              left: 0,
              width: spotlight.left,
              height: spotlight.bottom - spotlight.top,
            }}
            aria-hidden="true"
          />
          <div
            className={styles.shade}
            style={{
              top: spotlight.top,
              left: spotlight.right,
              right: 0,
              height: spotlight.bottom - spotlight.top,
            }}
            aria-hidden="true"
          />
          <div
            className={styles.focusRing}
            style={{
              top: spotlight.top,
              left: spotlight.left,
              width: spotlight.right - spotlight.left,
              height: spotlight.bottom - spotlight.top,
            }}
            aria-hidden="true"
          />
        </>
      ) : (
        <div className={styles.shade} style={{ inset: 0 }} aria-hidden="true" />
      )}
      <aside
        className={`${styles.callout} ${placement === 'top' ? styles.top : ''}`}
        role="status"
        aria-label={`앱 사용 안내 ${step}/${totalSteps} 단계`}
      >
        <div className={styles.header}>
          <span className={styles.eyebrow}>처음 사용 가이드</span>
          {onSkip ? (
            <button type="button" className={styles.skip} onClick={onSkip}>
              건너뛰기
            </button>
          ) : null}
        </div>
        <div className={styles.progress} aria-label={`${step}/${totalSteps} 단계`}>
          {Array.from({ length: totalSteps }, (_, index) => index + 1).map((number) => (
            <span key={number} className={number <= step ? styles.activeStep : ''} />
          ))}
          <span className={styles.stepNumber}>
            {step} / {totalSteps}
          </span>
        </div>
        <strong className={styles.title}>{title}</strong>
        <p>{description}</p>
        {action ? (
          <button type="button" className={styles.action} onClick={action.onClick}>
            {action.label} <span aria-hidden="true">→</span>
          </button>
        ) : (
          <span className={styles.hint}>강조된 곳을 눌러 다음 단계로 이동해요 ↗</span>
        )}
        {secondaryAction ? (
          <button
            type="button"
            className={styles.secondaryAction}
            onClick={secondaryAction.onClick}
          >
            {secondaryAction.label} <span aria-hidden="true">→</span>
          </button>
        ) : null}
      </aside>
    </>,
    document.body,
  );
}
