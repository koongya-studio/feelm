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
 * 오류 원인은 제목이 아니라 `description`에서 가른다. 제목은 화면이 무엇을 못 했는지를
 * 적고, 보조 문구는 `failure.ts`의 `failureDescription(error)`가 네트워크 단절과 서버
 * 실패를 나눠 준다(업무 S15P21E106-374 · `frontend-structure.md` 7.4). 처음에는 원인을
 * 구분하지 않기로 했으나(팀 결정 2026-09-08 — 인증 화면이 범위 밖이라 401을 다룰 수단이
 * 없었다) 실제 API 연동 뒤 백엔드가 내려간 홈에서 사용자가 무엇을 해야 할지 알 수 없어
 * 바꿨다. 401은 여전히 이 컴포넌트의 일이 아니다 — 세션 계층이 처리한다.
 *
 * `role="status"`는 화면 전체를 덮는 형태에만 붙인다. 2.1처럼 인라인 안내가 여러 개
 * 붙는 화면에서 라이브 영역을 여럿 두면 보조기술이 동시에 여러 개를 읽는다
 * (`common-components.md` 6.5는 스낵바와 진행 상태에만 `role="status"`를 정하고 있다).
 *
 * 제목의 요소는 `titleAs`로 고른다. 화면에 다른 `h1`이 없는 2.4 · 2.4-A의 오류 상태는
 * 이 제목이 그 화면의 제목이 된다(화면마다 `h1` 하나 — `common-components.md` 6.5).
 */
import styles from './StatusView.module.css';
import type { ReactNode } from 'react';

type Props = {
  /** `error`만 필름 프레임 모양의 `!` 표식을 붙인다 */
  variant?: 'error' | 'empty';
  /** `screen`은 2.2-A 형태로 화면을 채우고, `inline`은 섹션 자리에 들어간다 */
  size?: 'screen' | 'inline';
  /** 화면에 다른 `h1`이 없을 때 `h1`로 둔다 */
  titleAs?: 'p' | 'h1';
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
  titleAs = 'p',
  title,
  description,
  children,
}: Props) {
  const Title = titleAs;

  return (
    <div
      className={styles.area}
      data-size={size}
      data-variant={variant}
      role={size === 'screen' ? 'status' : undefined}
    >
      {variant === 'error' && size === 'screen' ? (
        <span className={styles.mark} aria-hidden="true">
          <span className={styles.markStrip} data-edge="top" />!
          <span className={styles.markStrip} data-edge="bottom" />
        </span>
      ) : null}

      <div className={styles.text}>
        <Title className={styles.title}>{title}</Title>
        {description ? <p className={styles.description}>{description}</p> : null}
      </div>

      {children ? <div className={styles.actions}>{children}</div> : null}
    </div>
  );
}
