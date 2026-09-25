import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '../../contexts/authContext';
import type { ShellAccount } from '../../hooks/useShellAccount';
import { path } from '../../routes/paths';
import Avatar from '../Avatar';
import { ChevronRightIcon } from '../Icon';
import styles from './DesktopAccountMenu.module.css';

type Props = {
  account: ShellAccount;
};

export default function DesktopAccountMenu({ account }: Props) {
  const { signOut } = useAuth();
  const navigate = useNavigate();
  const root = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);

  useEffect(() => {
    if (!open) return;

    function onPointerDown(event: PointerEvent) {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
        root.current?.querySelector('button')?.focus();
      }
    }

    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  async function handleSignOut() {
    if (signingOut) return;
    setSigningOut(true);
    try {
      await signOut();
    } finally {
      void navigate(path.login(), { replace: true });
    }
  }

  return (
    <div ref={root} className={styles.root}>
      <button
        type="button"
        className={styles.trigger}
        aria-label={`${account.nickname} 계정 메뉴`}
        aria-expanded={open}
        aria-haspopup="menu"
        onClick={() => setOpen((value) => !value)}
      >
        <Avatar size={36} nickname={account.nickname} src={account.avatarUrl} />
        <span className={styles.accountText}>
          <span className={styles.accountName}>{account.nickname}</span>
          {account.email ? <span className={styles.accountEmail}>{account.email}</span> : null}
        </span>
        <span className={styles.chevron}>
          <ChevronRightIcon />
        </span>
      </button>
      {open ? (
        <div className={styles.menu} role="menu" aria-label="계정">
          <div className={styles.menuAccount}>
            <span className={styles.menuCaption}>로그인한 계정</span>
            <strong className={styles.menuName}>{account.nickname}</strong>
            {account.email ? <span className={styles.menuEmail}>{account.email}</span> : null}
          </div>
          <button
            type="button"
            role="menuitem"
            className={styles.signOut}
            disabled={signingOut}
            onClick={() => void handleSignOut()}
          >
            로그아웃
          </button>
        </div>
      ) : null}
    </div>
  );
}
