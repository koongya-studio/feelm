/*
 * 수치를 나란히 보여주는 줄.
 *
 * 와이어프레임 7.1은 `본 영화 / 평가` 2열, 7.2-A · 7.2-B는 `필름 100프레임 · 팝콘
 * 100알`을 한 문장으로 적었다. 레퍼런스(`Social Profile UI Design`)는 같은 정보를
 * **3열 수치**로 배치하고, 그쪽이 문장보다 빨리 읽힌다.
 *
 * 열 수는 항목 수를 따른다. 2개든 3개든 같은 폭으로 나뉜다.
 *
 * **팔로워 · 팔로잉 같은 수치는 쓰지 않는다.** Feelm에 없는 개념이다
 * (screen-spec-my.md 3.4).
 */
import styles from './StatRow.module.css';

export type Stat = {
  label: string;
  value: string;
};

type Props = {
  items: Stat[];
  /**
   * 어두운 면 위에 놓이는지. 7.2-A · 7.2-B의 히어로 안에서 쓸 때 true다.
   * 밝은 면에서는 잉크, 어두운 면에서는 흰 글자를 쓴다.
   */
  onDark?: boolean;
};

export default function StatRow({ items, onDark = false }: Props) {
  return (
    <dl className={styles.row} data-on-dark={onDark}>
      {items.map((item) => (
        <div key={item.label} className={styles.item}>
          <dt className={styles.label}>{item.label}</dt>
          <dd className={styles.value}>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}
