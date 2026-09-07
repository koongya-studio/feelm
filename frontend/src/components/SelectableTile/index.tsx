/*
 * 선택 · 해제되는 타일.
 * 와이어프레임 1.2 구독 OTT 초기 설정의 목록 항목이다.
 *
 * 실측값: padding 14px 16px · radius 14px · 이름 15px/700 · 요소 간격 13px
 * 선택됨   : 테두리 1px #3C3A36, box-shadow 0 3px 10px rgba(0,0,0,0.07)
 * 선택 안 됨: 테두리 1px rgba(0,0,0,0.15), 그림자 없음
 * 오른쪽 표시: 24px 원. 선택됨은 #3C3A36 채움 + 흰 체크, 아니면 1.5px 테두리만
 *
 * 선택 상태를 색으로만 알리지 않도록 `aria-pressed`로 상태를 전달한다
 * (common-components.md 6.5).
 */
import { CheckIcon } from '../Icon';
import styles from './SelectableTile.module.css';
import type { ReactNode } from 'react';

type Props = {
  /** 타일 이름. 버튼의 접근 가능한 이름이 된다 */
  label: string;
  /** 왼쪽 표시 영역. 1.2는 OttBadge를 넣는다 */
  leading?: ReactNode;
  selected: boolean;
  onToggle: () => void;
};

export default function SelectableTile({ label, leading, selected, onToggle }: Props) {
  return (
    <button
      type="button"
      className={styles.tile}
      data-selected={selected}
      aria-pressed={selected}
      onClick={onToggle}
    >
      {leading ? <span className={styles.leading}>{leading}</span> : null}

      <span className={styles.label}>{label}</span>

      <span className={styles.check} aria-hidden="true">
        {selected ? <CheckIcon /> : null}
      </span>
    </button>
  );
}
