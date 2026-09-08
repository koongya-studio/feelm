/*
 * 오류 · 빈 데이터 상태 표시.
 * `common-components.md` 3.1이 `StatusView`로 예정해 둔 컴포넌트다(만들 업무 16).
 *
 * 와이어프레임의 상태 화면은 2.2-A 하나뿐이라 그 실측값이 기준이다.
 * 표식 76×56 · 테두리 2px · 반경 8px · `!` 26px/800 · 제목 17px/700 ·
 * 설명 13px/1.6 · 바깥 여백 0 34px · 요소 간격 16px.
 * 동작 버튼은 화면마다 다르므로 `children`으로 받는다.
 *
 * `size="inline"`은 2.1처럼 한 화면에 섹션이 여러 개일 때 그 섹션 자리에만 넣는
 * 경우다. 와이어프레임에 대응 표현이 없어 표식 없이 2.1의 글자 크기 체계를 따른다
 * (제목 13px/700 · 설명 12.5px). 화면 전체를 오류로 덮지 않기 위한 것이다
 * (팀 결정 2026-09-08).
 *
 * 로딩 상태는 만들지 않는다. 데이터를 받기 전에는 아무것도 그리지 않기로 정했다
 * (팀 결정 2026-09-08).
 *
 * 오류 종류(503 · 401 · 네트워크 단절)를 문구로 구분하지 않는다. 인증 화면(0.x)이
 * 범위 밖이라 401을 따로 다룰 수단이 없다(팀 결정 2026-09-08 —
 * `screen-spec.md` 2.2-A 미확정 항목).
 */
import styles from './StatusView.module.css';
import type { ReactNode } from 'react';

type Props = {
  /** `error`만 필름 프레임 모양의 `!` 표식을 붙인다 */
  variant?: 'error' | 'empty';
  /** `screen`은 2.2-A 형태로 화면을 채우고, `inline`은 섹션 자리에 들어간다 */
  size?: 'screen' | 'inline';
  /** 무엇이 문제인지 */
  title: string;
  /** 회복 방법이나 보충 안내 (`common-components.md` 6.6) */
  description?: string;
  /** 동작 버튼 · 링크. 없으면 영역을 만들지 않는다 */
  children?: ReactNode;
};

export default function StatusView({
  variant = 'error',
  size = 'screen',
  title,
  description,
  children,
}: Props) {
  return (
    <div className={styles.area} data-size={size} data-variant={variant} role="status">
      {variant === 'error' && size === 'screen' ? (
        <span className={styles.mark} aria-hidden="true">
          <span className={styles.markStrip} data-edge="top" />!
          <span className={styles.markStrip} data-edge="bottom" />
        </span>
      ) : null}

      <div className={styles.text}>
        <p className={styles.title}>{title}</p>
        {description ? <p className={styles.description}>{description}</p> : null}
      </div>

      {children ? <div className={styles.actions}>{children}</div> : null}
    </div>
  );
}
