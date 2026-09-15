/*
 * 한 줄 입력 필드.
 *
 * 와이어프레임 0.4 · 0.6 · 0.7 · 0.7-A 네 화면이 같은 모양의 입력을 쓴다.
 * `common-components.md` 3.1의 "두 개 이상 화면에서 쓰임" 기준에 따라 공통에 둔다.
 *
 * 와이어프레임은 필드 안 왼쪽에 아이콘을 두고 라벨 없이 placeholder만 보여준다.
 * placeholder만으로는 입력 후 무엇을 적는 칸인지 사라지므로, 보이는 형태는 유지하되
 * 라벨을 `VisuallyHidden`으로 넣어 보조기술이 읽게 했다(`common-components.md` 6.5).
 *
 * `[?]` 오류 문구의 표시 위치와 문구는 와이어프레임에 없다. `error`를 받으면 필드
 * 아래에 두고 테두리만 바꾸는 최소 형태로 두었다.
 */
import { useId } from 'react';
import type { InputHTMLAttributes, ReactNode } from 'react';
import VisuallyHidden from '../VisuallyHidden';
import styles from './TextField.module.css';

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & {
  /** 보조기술이 읽는 이름. 화면에는 보이지 않는다 */
  label: string;
  /** 필드 안 왼쪽 아이콘 */
  leading?: ReactNode;
  /**
   * 필드 안 오른쪽에 붙는 동작 (0.4 `인증요청` · `확인`).
   *
   * 노드가 아니라 값으로 받는다. 공통 `Button`은 `width: 100%`라 필드 안에 넣으면
   * 입력을 밀어내고, 화면마다 인라인 버튼을 새로 만들면 같은 모양이 여러 벌 생긴다.
   */
  action?: { label: string; onClick?: () => void; disabled?: boolean };
  /** 필드 안 오른쪽 표시값 (0.7 남은 시간). 누를 수 없는 것만 넣는다 */
  trailing?: ReactNode;
  /** 필드 아래 보조 문구 (0.7-A `8자 이상, 영문과 숫자 포함`) */
  hint?: string;
  /** 오류 문구. 있으면 테두리가 바뀌고 보조기술에 오류로 알린다 */
  error?: string;
};

export default function TextField({
  label,
  leading,
  action,
  trailing,
  hint,
  error,
  ...rest
}: Props) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <div className={styles.field}>
      <div className={styles.control} data-invalid={error ? 'true' : undefined}>
        {leading ? <span className={styles.leading}>{leading}</span> : null}

        <VisuallyHidden>
          <label htmlFor={id}>{label}</label>
        </VisuallyHidden>

        <input
          id={id}
          className={styles.input}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          {...rest}
        />

        {action ? (
          <button
            type="button"
            className={styles.action}
            onClick={action.onClick}
            disabled={action.disabled}
          >
            {action.label}
          </button>
        ) : null}

        {trailing ? <span className={styles.trailing}>{trailing}</span> : null}
      </div>

      {error ? (
        <p id={`${id}-error`} className={styles.error} role="alert">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className={styles.hint}>
          {hint}
        </p>
      ) : null}
    </div>
  );
}
