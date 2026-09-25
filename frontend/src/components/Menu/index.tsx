/*
 * 팝오버 메뉴.
 * 화면 위에 띄우는 공용 메뉴다. 바텀시트가 아니라 화면 오른쪽에 붙는
 * 작은 팝오버다(common-components.md 4.2).
 *
 * 실측값: right 12px · width 148px · radius 14px · padding 14px
 *         box-shadow 0 8px 24px rgba(0,0,0,0.1)
 *
 * 세로 위치는 호출한 화면의 기준 요소에 따라 달라지므로 `top`으로 받는다.
 *
 * 가로 위치는 기본이 화면 오른쪽 끝이고, 기준 요소에 직접 맞춰야 하는 화면은 `left`를
 * 넘긴다. 값을 주지 않으면 오른쪽 끝에 붙는다.
 *
 * 접근성(frontend-structure.md 10장)
 * - 열릴 때 초점을 메뉴 안으로 옮긴다
 * - Esc로 닫는다
 * - Tab이 메뉴 밖으로 나가지 않는다
 *
 * role="menu"를 쓰지 않았다. 그 역할은 화살표 키 탐색까지 갖춰야 하는데
 * 와이어프레임에 해당 동작 근거가 없다. 이름이 붙은 컨테이너와 버튼으로 두어
 * 표준 Tab 탐색을 그대로 쓴다.
 *
 * 닫기 버튼이 없는 팝오버라 Esc와 배경 탭으로 닫는다.
 */
import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import styles from './Menu.module.css';

type Props = {
  /** 접근 가능한 이름 */
  label: string;
  /** 메뉴 상단에 보이는 제목 */
  title?: ReactNode;
  /** 화면 위쪽에서의 거리(px) */
  top: number;
  /** 왼쪽에서의 거리(px). 생략하면 오른쪽 끝에 붙는다 */
  left?: number;
  onClose: () => void;
  children: ReactNode;
};

/* 비활성 요소는 초점을 받지 못하므로 제외한다 */
const FOCUSABLE = [
  'button:not([disabled])',
  '[href]',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

export default function Menu({ label, title, top, left, onClose, children }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;

    if (!container) {
      return;
    }

    const first = container.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? container).focus();
  }, []);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();

        return;
      }

      if (event.key !== 'Tab') {
        return;
      }

      const container = containerRef.current;

      if (!container) {
        return;
      }

      const focusable = Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE));

      if (focusable.length === 0) {
        return;
      }

      const firstItem = focusable[0];
      const lastItem = focusable[focusable.length - 1];
      const active = document.activeElement;

      if (event.shiftKey && (active === firstItem || active === container)) {
        event.preventDefault();
        lastItem.focus();

        return;
      }

      if (!event.shiftKey && active === lastItem) {
        event.preventDefault();
        firstItem.focus();
      }
    }

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [onClose]);

  return (
    <>
      {/* 배경 탭으로 닫는다. 화면에 보이는 요소가 아니므로 색을 넣지 않는다 */}
      <div className={styles.backdrop} onClick={onClose} data-testid="menu-backdrop" />

      <div
        ref={containerRef}
        className={styles.menu}
        style={
          left === undefined
            ? { top: `${top}px` }
            : { top: `${top}px`, left: `${left}px`, right: 'auto' }
        }
        aria-label={label}
        role="group"
        tabIndex={-1}
      >
        {title ? <p className={styles.title}>{title}</p> : null}
        {children}
      </div>
    </>
  );
}
