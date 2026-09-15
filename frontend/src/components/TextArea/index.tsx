/*
 * 한 줄 감상 입력.
 * 와이어프레임 2.5의 `한 줄 감상 남기기 (선택)` 자리다.
 * 실측값: 높이 50px · radius 13px · 테두리 1px rgba(0,0,0,0.18) · 좌우 여백 16px ·
 * 글자 13.5px · 자리표시자 rgba(60,58,54,0.45)
 *
 * 한줄평은 200자 이하다(API-036). 와이어프레임은 한 줄로 그려져 있지만 200자는 한 줄에
 * 들어가지 않으므로 `textarea`로 두고 높이는 와이어프레임 값을 유지한다.
 *
 * 남은 글자 수는 와이어프레임에 없어 표시하지 않는다. 입력 길이는 maxLength로 막는다.
 */
import { useId } from 'react';
import VisuallyHidden from '../VisuallyHidden';
import styles from './TextArea.module.css';

const MAX_LENGTH = 200;

type Props = {
  /** 접근 가능한 이름. 화면에는 보이지 않는다 */
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
};

export default function TextArea({ label, value, onChange, placeholder, disabled = false }: Props) {
  const id = useId();

  return (
    <div className={styles.field}>
      <label htmlFor={id}>
        <VisuallyHidden>{label}</VisuallyHidden>
      </label>

      <textarea
        id={id}
        className={styles.input}
        value={value}
        placeholder={placeholder}
        maxLength={MAX_LENGTH}
        disabled={disabled}
        rows={1}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}
