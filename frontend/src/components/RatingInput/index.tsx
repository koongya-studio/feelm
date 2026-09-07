/*
 * 별점 입력.
 * 와이어프레임 2.5의 별 5개(40px, 간격 10px)와 `4.5 / 5.0 · 0.5점 단위` 표기다.
 *
 * 계약은 0.5~5.0의 0.5 배수만 받고 4.49 같은 값을 반올림하지 않고 거절한다
 * (API-036 `multipleOf: 0.5`). 그래서 화면에서 0.5 배수만 만든다.
 *
 * 조작은 네이티브 `input[type=range]`가 담당한다. 별 그림 위에 투명하게 겹쳐 두어
 * 탭 · 드래그 · 키보드(화살표 · Home · End)가 모두 동작하고, 현재 값이 보조기술에
 * 읽힌다(common-components.md 6.5 — 별점 입력은 키보드로 조작 가능하고 현재 값이
 * 이름으로 읽혀야 한다).
 *
 * `0`은 아직 고르지 않은 상태를 뜻하는 화면 전용 값이다. 계약의 최소값은 0.5이므로
 * 0은 서버로 보내지 않는다.
 */
import RatingStars from '../RatingStars';
import styles from './RatingInput.module.css';

const MAX_SCORE = 5;
const STEP = 0.5;

type Props = {
  /** 0은 선택 안 함. 그 밖에는 0.5~5.0의 0.5 배수 */
  value: number;
  onChange: (value: number) => void;
  disabled?: boolean;
};

export default function RatingInput({ value, onChange, disabled = false }: Props) {
  return (
    <div className={styles.rating}>
      <div className={styles.control}>
        <span className={styles.visual}>
          <RatingStars score={value} size={40} gap={10} />
        </span>

        <input
          type="range"
          className={styles.slider}
          min={0}
          max={MAX_SCORE}
          step={STEP}
          value={value}
          disabled={disabled}
          aria-label="별점"
          aria-valuetext={value === 0 ? '선택 안 함' : `${value.toFixed(1)}점 / ${MAX_SCORE}점`}
          onChange={(event) => onChange(Number(event.target.value))}
        />
      </div>

      <p className={styles.value}>
        {value === 0
          ? `별점을 선택해 주세요 · ${STEP}점 단위`
          : `${value.toFixed(1)} / 5.0 · ${STEP}점 단위`}
      </p>
    </div>
  );
}
