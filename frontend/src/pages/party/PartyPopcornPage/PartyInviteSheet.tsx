import { useEffect, useRef } from 'react';
import PartyInviteSearch from '../../../components/PartyInviteSearch';
import type { SearchedUser } from '../../../types/user';
import styles from './PartyInviteSheet.module.css';

type Props = {
  busy: boolean;
  maxMembers: number;
  invitedIds: Set<number>;
  notice: string;
  onInvite: (user: SearchedUser) => void;
  onClose: () => void;
};

const FOCUSABLE = 'button:not(:disabled), input:not(:disabled), [tabindex]:not([tabindex="-1"])';

export default function PartyInviteSheet({
  busy,
  maxMembers,
  invitedIds,
  notice,
  onInvite,
  onClose,
}: Props) {
  const sheetRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const trigger = document.activeElement;
    closeRef.current?.focus();
    return () => {
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus();
    };
  }, []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !sheetRef.current) return;
      const items = [...sheetRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      const inside = sheetRef.current.contains(active);
      if (event.shiftKey && (active === first || !inside)) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && (active === last || !inside)) {
        event.preventDefault();
        first?.focus();
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className={styles.overlay}>
      <button
        type="button"
        className={styles.scrim}
        tabIndex={-1}
        aria-hidden="true"
        onClick={onClose}
      />
      <div
        ref={sheetRef}
        id="party-invite-sheet"
        className={styles.sheet}
        role="dialog"
        aria-modal="true"
        aria-label="친구 초대"
      >
        <header className={styles.header}>
          <h2 className={styles.title}>친구 초대</h2>
          <button ref={closeRef} type="button" className={styles.close} onClick={onClose}>
            닫기
          </button>
        </header>
        <div className={styles.body}>
          <PartyInviteSearch
            disabled={busy}
            isPicked={(user) => invitedIds.has(user.userId)}
            isPickDisabled={(user) => invitedIds.has(user.userId)}
            pickLabel={(user) => (invitedIds.has(user.userId) ? '초대 완료' : '초대')}
            onPick={onInvite}
            note={`이 파티는 파티장 포함 최대 ${maxMembers}명까지예요`}
          />
          <p className={styles.notice} role="status">
            {notice}
          </p>
        </div>
      </div>
    </div>
  );
}
