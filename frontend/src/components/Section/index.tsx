/*
 * 섹션 컨테이너.
 * 와이어프레임 2.1 홈의 각 섹션과 3.1 취향 필름의 카드에서 같은 사양으로 확인된다.
 * 테두리 1px rgba(0,0,0,0.15), 반경 16px, 안쪽 여백 14px, 요소 간격 12px
 * 라벨은 12.5px/700, 옅은 색이다.
 *
 * common-components.md의 SectionHeader를 대체한다. 그 문서는 제목과 링크로 이루어진
 * 헤더로 적었으나, 와이어프레임의 반복 단위는 라벨이 붙은 테두리 컨테이너다.
 *
 * 범용 카드 컨테이너가 아니다. 와이어프레임이 이 사양으로 반복해서 쓰는 섹션에만
 * 사용하고, 카드 안에 카드를 넣지 않는다(common-components.md 7장).
 */
import type { ReactNode } from 'react';
import styles from './Section.module.css';

type Props = {
  /** 섹션 라벨. 예: 오늘의 추천, 요즘 취향 흐름 */
  label?: ReactNode;
  children: ReactNode;
};

export default function Section({ label, children }: Props) {
  return (
    <section className={styles.section}>
      {label ? <h2 className={styles.label}>{label}</h2> : null}
      {children}
    </section>
  );
}
