/*
 * 한 줄씩 누르는 목록 — 7.8 설정 · 7.8-A 계정 관리.
 *
 * 줄 하나는 **이동**(`to`)이거나 **동작**(`onClick`)이거나 **값 표시**(`value`만)다.
 * 와이어프레임은 줄마다 테두리 상자를 따로 그렸지만, 7.1 메뉴와 같은 이유로 한 상자 안을
 * 선으로 나눈다 — 상자가 줄마다 있으면 목록이 상자 더미가 된다(MyDashboardPage.module.css).
 *
 * 묶음 제목(`label`)은 목록의 이름이 된다. 7.8의 `계정` · `공개 및 알림` · `기타`다.
 */
import { useId } from 'react';
import { Link } from 'react-router';
import { ChevronRightIcon } from '../Icon';
import styles from './ActionList.module.css';

export type ActionItem = {
  label: string;
  /** 이동할 곳 */
  to?: string;
  /** 누르면 할 일. `to`와 함께 쓰지 않는다 */
  onClick?: () => void;
  /** 오른쪽 끝에 보이는 값. 이것만 있으면 누를 수 없는 줄이다 */
  value?: string;
};

type Props = {
  label?: string;
  items: ActionItem[];
};

export default function ActionList({ label, items }: Props) {
  const labelId = useId();

  return (
    <section className={styles.group} aria-labelledby={label ? labelId : undefined}>
      {label ? (
        <h2 id={labelId} className={styles.label}>
          {label}
        </h2>
      ) : null}

      <ul className={styles.list}>
        {items.map((item) => (
          <li key={item.label}>
            <Row item={item} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function Row({ item }: { item: ActionItem }) {
  const content = (
    <>
      <span className={styles.itemLabel}>{item.label}</span>
      {item.value ? <span className={styles.value}>{item.value}</span> : null}
      {item.to || item.onClick ? (
        <span className={styles.chevron} aria-hidden="true">
          <ChevronRightIcon />
        </span>
      ) : null}
    </>
  );

  if (item.to) {
    return (
      <Link to={item.to} className={styles.row}>
        {content}
      </Link>
    );
  }

  if (item.onClick) {
    return (
      <button type="button" className={styles.row} onClick={item.onClick}>
        {content}
      </button>
    );
  }

  return <div className={styles.row}>{content}</div>;
}
