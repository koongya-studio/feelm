/*
 * 켜고 끄는 스위치.
 *
 * 6.1 구독 OTT · 7.6-A 공개 한줄평 · 7.8-C 알림 설정이 같은 모양을 쓴다. 켜면 손잡이가
 * 오른쪽으로 가고 트랙이 검정이 된다. 빨강을 쓰지 않는다 — 켜짐은 선택 표식이 아니라
 * 상태이고, 트랙은 면이다(`design-concept.md` 2.3).
 *
 * 이름은 `label`(보조기술용 문자열)이나 `labelledBy`(화면에 보이는 글자의 id) 중 하나로 준다.
 * 스위치 아래 설명 글이 있으면 `describedBy`로 그 id를 잇는다(7.8-C 푸시 줄).
 */
import styles from './Switch.module.css';

type Props = {
  checked: boolean;
  onChange: (next: boolean) => void;
  label?: string;
  labelledBy?: string;
  describedBy?: string;
  disabled?: boolean;
};

export default function Switch({
  checked,
  onChange,
  label,
  labelledBy,
  describedBy,
  disabled = false,
}: Props) {
  return (
    <button
      type="button"
      className={styles.track}
      role="switch"
      aria-checked={checked}
      aria-label={label}
      aria-labelledby={labelledBy}
      aria-describedby={describedBy}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className={styles.knob} aria-hidden="true" />
    </button>
  );
}
