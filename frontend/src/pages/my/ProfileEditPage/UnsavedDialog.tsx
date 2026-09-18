/*
 * 저장하지 않은 변경이 있을 때 묻는 창 (업무 S15P21E106-616)
 *
 * 7.2에서 한 항목을 고치다가 다른 항목의 연필을 누르거나 화면을 떠나려 할 때 뜬다.
 * 선택지는 셋이다 — 저장하고 넘어가기 · 버리고 넘어가기 · 계속 편집.
 *
 * `ConfirmDialog`는 두 갈래라 쓰지 못했다. 동작은 같게 맞췄다 — `alertdialog`, 뜨면
 * **계속 편집**(되돌릴 수 있는 쪽)에 초점, Escape는 계속 편집, 저장 중에는 닫히지 않는다.
 */
import { useEffect, useId, useRef } from 'react';
import Button from '../../../components/Button';
import styles from './ProfileEditPage.module.css';

type Props = {
  /** 저장하고 넘어갈 때의 버튼 문구. `저장하고 나가기`처럼 어디로 가는지 적는다 */
  saveLabel: string;
  discardLabel: string;
  onSave: () => void;
  onDiscard: () => void;
  onKeep: () => void;
  saving: boolean;
};

export default function UnsavedDialog({
  saveLabel,
  discardLabel,
  onSave,
  onDiscard,
  onKeep,
  saving,
}: Props) {
  const id = useId();
  const keepRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    keepRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !saving) {
        event.stopImmediatePropagation();
        onKeep();
      }
    }

    window.addEventListener('keydown', onKeyDown, true);

    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [saving, onKeep]);

  return (
    <div className={styles.dialogLayer}>
      <div
        className={styles.dialog}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-description`}
      >
        <p id={`${id}-title`} className={styles.dialogTitle}>
          저장하지 않은 변경사항이 있어요
        </p>
        <p id={`${id}-description`} className={styles.dialogDescription}>
          지금 고친 내용을 저장할까요?
        </p>

        <div className={styles.dialogActions}>
          <Button disabled={saving} onClick={onSave}>
            {saving ? '저장 중' : saveLabel}
          </Button>
          <Button variant="secondary" disabled={saving} onClick={onDiscard}>
            {discardLabel}
          </Button>
          <button
            ref={keepRef}
            type="button"
            className={styles.dialogKeep}
            disabled={saving}
            onClick={onKeep}
          >
            계속 편집
          </button>
        </div>
      </div>
    </div>
  );
}
