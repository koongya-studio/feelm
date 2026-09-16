/*
 * 성별 선택. 계약의 `Gender` 세 값을 한 줄에 균등하게 놓는다.
 *
 * 표시 문구는 와이어프레임 0.4 그대로이고 값만 계약에 대응시킨다. 0.4 이메일 회원가입과
 * 0.3-A 소셜 가입 정보 입력이 함께 쓰게 되어 공통으로 올렸다(frontend-structure.md 5.1,
 * 업무 S15P21E106-499).
 *
 * `select`가 아니라 버튼 세 개인 것은 와이어프레임이 그렇게 그려 둔 것이다. 선택 상태는
 * `aria-pressed`로 알린다.
 */
import styles from './GenderField.module.css';
import type { Gender } from '../../types/auth';

/** 와이어프레임 0.4의 선택지와 나열 순서 */
const GENDERS: { label: string; value: Gender }[] = [
  { label: '여성', value: 'FEMALE' },
  { label: '남성', value: 'MALE' },
  { label: '선택 안 함', value: 'OTHER' },
];

type Props = {
  value: Gender | null;
  /** 고른 값과 그 표시 문구. 문구는 타자기 키를 누르는 데 쓴다 */
  onSelect: (gender: Gender, label: string) => void;
};

export default function GenderField({ value, onSelect }: Props) {
  return (
    <fieldset className={styles.genders}>
      <legend className={styles.legend}>성별</legend>

      {GENDERS.map((option) => (
        <button
          key={option.value}
          type="button"
          className={styles.gender}
          aria-pressed={value === option.value}
          onClick={() => onSelect(option.value, option.label)}
        >
          {option.label}
        </button>
      ))}
    </fieldset>
  );
}
