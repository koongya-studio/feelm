/*
 * 화면 헤더.
 * 와이어프레임에서 확인된 헤더 구성은 화면마다 다르다. 뒤로 가기 요소의 유무가
 * 레이아웃과 무관하다는 점을 반영해 화면이 직접 필요한 요소를 지정한다.
 *
 * | 화면 | 뒤로 가기 | 우측 액션 |
 * | 3.1 · 5.1 · 7.1 탭 | 없음 | 공통 헤더 `AppHeader`가 따로 맡는다(업무 -676). 7.1은 그 아래 `flush` 제목 줄 |
 * | 2.2 · 1.1 · 2.6 · 2.7 | 없음 | 없음 |
 * | 3.2 · 5.8 · 5.9 · 2.4 · 2.5 · 3.1-A | 있음 | 없음 |
 *
 * 화면마다 h1을 하나만 두기 위해 제목은 h1으로 렌더한다.
 *
 * **뒤로 가기는 `useBack`이다**(업무 -610). 앱 안에서 왔으면 히스토리로, 새 탭 · 외부
 * 링크로 바로 들어왔으면 화면이 준 `fallbackTo`로 간다. 예전의 `navigate(-1)`은 그런
 * 경우 앱 밖이나 `about:blank`로 나가 버렸다.
 */
import type { ReactNode } from 'react';
import { useBack } from '../../hooks/useBack';
import { path } from '../../routes/paths';
import { ChevronLeftIcon } from '../Icon';
import VisuallyHidden from '../VisuallyHidden';
import styles from './ScreenHeader.module.css';

type Props = {
  /** 화면 제목. 문자열 대신 노드를 넘겨 로고 등을 표시할 수 있다 */
  title: ReactNode;
  /** 제목 아래 보조 문구 */
  subtitle?: ReactNode;
  /** 뒤로 가기 표시 여부 */
  showBack?: boolean;
  /**
   * 이 앱의 이전 항목이 없을 때(새 탭 · 외부 링크) 뒤로 가기가 갈 곳. 화면마다 자기 자리를
   * 준다 — 상세는 목록으로, 설정 하위는 설정으로, 알림은 MY로. 없으면 3.1 필름이다.
   */
  fallbackTo?: string;
  /** 우측 액션 영역 */
  action?: ReactNode;
  /**
   * 제목 정렬. 와이어프레임 2.2 · 2.2-A는 제목을 가운데 두고, 나머지 화면은
   * 왼쪽에 둔다. 기본값은 기존 화면과 같은 왼쪽이다.
   */
  align?: 'left' | 'center';
  /**
   * 위 여백(48px) 없이 붙인다. 공통 헤더(`AppHeader`) 아래에 화면 제목 줄을 한 번 더 둘 때
   * 쓴다(7.1 MY, 업무 -676).
   */
  flush?: boolean;
};

export default function ScreenHeader({
  title,
  subtitle,
  showBack = false,
  fallbackTo = path.film(),
  action,
  align = 'left',
  flush = false,
}: Props) {
  const back = useBack(fallbackTo);

  return (
    <header className={styles.header} data-align={align} data-flush={flush || undefined}>
      <div className={styles.row}>
        {showBack ? (
          <button type="button" className={styles.back} onClick={back}>
            <ChevronLeftIcon />
            <VisuallyHidden>뒤로 가기</VisuallyHidden>
          </button>
        ) : null}

        <h1 className={styles.title}>{title}</h1>

        {action ? <div className={styles.action}>{action}</div> : null}
      </div>

      {subtitle ? <p className={styles.subtitle}>{subtitle}</p> : null}
    </header>
  );
}
