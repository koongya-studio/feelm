/*
 * 우측 보조 패널의 카드 한 장 (업무 S15P21E106-783).
 *
 * 2026-09-21 시안 5종의 오른쪽 단은 같은 카드가 위아래로 쌓인 모양이다. **흰 면 · 제목 줄 ·
 * 제목 오른쪽 화살표**가 공통이고 그 안에 들어가는 것만 화면마다 다르다(취향 팝콘 · 취향
 * 리포트 · 추천 기준 · 최근 본 영화 ⋯). 그 공통 껍데기를 여기 한 번만 만든다 — 화면마다
 * 같은 카드를 다시 그리면 간격과 제목 크기가 조금씩 어긋난다.
 *
 * 화살표는 `to`를 준 카드에만 그린다. 갈 곳이 없는 카드에 화살표를 얹으면 누를 수 있는
 * 것처럼 보이고 눌리지 않는다.
 *
 * **제목 줄 전체가 링크가 아니라 화살표만 링크다.** 카드 안에 또 다른 링크(팝콘 행 · 영화
 * 줄)가 들어가므로 카드 전체를 링크로 만들면 링크 안에 링크가 생긴다.
 */
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { ChevronRightIcon } from '../Icon';
import styles from './AsidePanel.module.css';

type Props = {
  title: string;
  /** 제목 오른쪽 화살표가 가는 곳. 없으면 화살표를 그리지 않는다 */
  to?: string;
  /** 화살표에 읽히는 이름. `to`가 있으면 준다 */
  linkLabel?: string;
  /** 제목 오른쪽의 작은 표식. `새 리포트`처럼 지금 상태를 말한다 */
  mark?: ReactNode;
  /** 카드 안쪽을 어두운 면으로 둔다. 팝콘은 밝은 면에서 4종이 구분되지 않는다 */
  tone?: 'light' | 'dark';
  children: ReactNode;
};

export default function AsidePanel({
  title,
  to,
  linkLabel,
  mark,
  tone = 'light',
  children,
}: Props) {
  return (
    <section className={styles.panel} data-tone={tone}>
      <div className={styles.head}>
        <h2 className={styles.title}>{title}</h2>

        {mark ? <span className={styles.mark}>{mark}</span> : null}

        {to ? (
          <Link to={to} className={styles.link} aria-label={linkLabel ?? `${title} 자세히 보기`}>
            <ChevronRightIcon />
          </Link>
        ) : null}
      </div>

      <div className={styles.body}>{children}</div>
    </section>
  );
}
