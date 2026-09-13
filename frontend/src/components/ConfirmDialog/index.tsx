/*
 * 되돌릴 수 없는 동작 앞의 확인 창.
 *
 * 7.6-A 한줄평 삭제 · 7.2-B 사용자 차단 · 7.8-A-1 회원 탈퇴가 쓴다. 306에서 한줄평 시트
 * 안에 따로 두었던 것을 307에서 세 곳이 쓰게 되어 꺼냈다.
 *
 * - `alertdialog`다. 뜨면 **취소**에 초점을 둔다 — 실수로 Enter를 눌러도 되돌릴 수 있는 쪽
 * - Escape는 취소다. 처리 중(`busy`)에는 닫지 않는다
 * - 확인 버튼이 검정 알약이다. 이 창이 떠 있는 동안 화면의 주 동작은 이것 하나다
 */
import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';
import Button from '../Button';
import styles from './ConfirmDialog.module.css';

type Props = {
  title: string;
  description?: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
  /** 확인을 처리하는 중. 두 버튼을 막는다 */
  busy?: boolean;
};

export default function ConfirmDialog({
  title,
  description,
  confirmLabel,
  cancelLabel = '취소',
  onConfirm,
  onCancel,
  busy = false,
}: Props) {
  const id = useId();
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    dialogRef.current?.querySelector('button')?.focus();
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !busy) {
        // 뒤에 깔린 시트가 같은 키로 함께 닫히지 않게 한다
        event.stopImmediatePropagation();
        onCancel();
      }
    }

    // 캡처 단계에서 먼저 받는다. 시트의 Escape 처리보다 앞서야 창만 닫힌다
    window.addEventListener('keydown', onKeyDown, true);

    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [busy, onCancel]);

  return (
    <div className={styles.layer}>
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        aria-describedby={description ? `${id}-description` : undefined}
      >
        <p id={`${id}-title`} className={styles.title}>
          {title}
        </p>

        {description ? (
          <div id={`${id}-description`} className={styles.description}>
            {description}
          </div>
        ) : null}

        <div className={styles.actions}>
          <Button variant="secondary" disabled={busy} onClick={onCancel}>
            {cancelLabel}
          </Button>
          <Button disabled={busy} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
