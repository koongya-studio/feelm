/*
 * 화면 헤더.
 * 와이어프레임에서 확인된 헤더 구성은 화면마다 다르다. 뒤로 가기 요소의 유무가
 * 레이아웃과 무관하다는 점을 반영해 화면이 직접 필요한 요소를 지정한다.
 *
 * | 화면 | 뒤로 가기 | 우측 액션 |
 * | 3.1 필름 | 없음 | 검색 · 알림 (업무 -461에서 홈이 없어지며 옮겨왔다) |
 * | 2.2 · 1.1 · 2.6 · 2.7 | 없음 | 없음 |
 * | 3.2 · 5.8 · 5.9 · 2.4 · 2.5 · 3.1-A | 있음 | 없음 |
 *
 * 화면마다 h1을 하나만 두기 위해 제목은 h1으로 렌더한다.
 */
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
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
  /** 우측 액션 영역 */
  action?: ReactNode;
  /**
   * 제목 정렬. 와이어프레임 2.2 · 2.2-A는 제목을 가운데 두고, 나머지 화면은
   * 왼쪽에 둔다. 기본값은 기존 화면과 같은 왼쪽이다.
   */
  align?: 'left' | 'center';
};

export default function ScreenHeader({
  title,
  subtitle,
  showBack = false,
  action,
  align = 'left',
}: Props) {
  const navigate = useNavigate();

  return (
    <header className={styles.header} data-align={align}>
      <div className={styles.row}>
        {showBack ? (
          <button type="button" className={styles.back} onClick={() => navigate(-1)}>
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
