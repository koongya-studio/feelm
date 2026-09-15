/*
 * 일시적 피드백 막대.
 * 와이어프레임 2.3-A 관심없음 처리의 하단 막대다.
 *
 * 실측값: height 48px · radius 12px · background #3C3A36 · padding 0 16px
 *         본문 13px · 액션 13px/700 밑줄
 *
 * 상태 변화를 알리도록 role="status"를 둔다(frontend-structure.md 10장).
 *
 * `[?]` 노출 시간과 액션 가능 시간은 screen-spec.md 2.3-A의 미확정 항목이다.
 * 임의로 정하지 않기 위해 자동으로 사라지는 타이머를 넣지 않았다. 화면이 상태를
 * 없앨 때까지 유지한다.
 */
import type { ReactNode } from 'react';
import styles from './Snackbar.module.css';

type Props = {
  children: ReactNode;
  /** 액션 문구. 예: 실행 취소 */
  actionLabel?: string;
  onAction?: () => void;
};

export default function Snackbar({ children, actionLabel, onAction }: Props) {
  return (
    <div className={styles.snackbar} role="status">
      <span className={styles.message}>{children}</span>

      {actionLabel ? (
        <button type="button" className={styles.action} onClick={onAction}>
          {actionLabel}
        </button>
      ) : null}
    </div>
  );
}
