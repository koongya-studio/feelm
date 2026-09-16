/*
 * 공개 프로필 상단 비주얼. 7.2-A · 7.2-B가 쓴다.
 *
 * 레퍼런스 두 장(`Social Profile UI Design` · `10 UI_UX Design Trends`)이 공통으로
 * 쓰는 구성이다. **비주얼이 화면 상단을 소유한다** — 화면 맨 위부터 전체 폭으로 깔리고,
 * 뒤로 가기와 ⋯는 그 위에 떠 있는 반투명 원이며, 이름은 아래쪽 경계에 얹힌다.
 * 그 아래 본문이 덮고 올라온 판처럼 읽힌다.
 *
 * **배경은 무채색 어두운 면이다.** 한때 이 자리에 그 사람의 취향 필름 포스터를 흐려서
 * 깔았는데, 확정 방향이 **`포스터 색을 배경으로 번지게 하지 않습니다. 포스터의 색은
 * 포스터 프레임 안에 머뭅니다`**로 못박고 있어 걷어냈다(`design-concept.md` 3장,
 * 2026-09-11). 레퍼런스에서 가져올 것은 **구성**이지 색의 출처가 아니다.
 *
 * 첫 구현은 그 구성 자체도 놓쳤다. 밝은 헤더를 위에 두고 그 아래 짧은 어두운 상자를
 * 놓아 가운데가 비어 보였다. 고친 것 세 가지다.
 * - 헤더를 없애고 비주얼을 화면 맨 위까지 올렸다. 뒤로 가기는 그 위로 띄운다
 * - 높이를 화면의 절반 이상으로 키웠다(상한 520px)
 * - 이름을 한가운데가 아니라 아래쪽 경계에 둔다
 *
 * **아바타와 한 줄 소개는 없다**(업무 -459). 계약(API-014 · API-062)에 프로필 이미지도
 * 소개 문구도 없어 목업이 지어낸 값이었다. 비주얼이 담는 것은 이름과 수치뿐이다.
 */
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import VisuallyHidden from '../VisuallyHidden';
import { ChevronLeftIcon, MoreVerticalIcon } from '../Icon';
import styles from './ProfileHero.module.css';

type Props = {
  nickname: string;
  /**
   * 비주얼 위 가운데에 뜨는 작은 라벨. 7.2-A가 `미리보기`를 표시한다 —
   * 헤더를 없앴으므로 이 화면이 무엇인지 알릴 자리가 필요하다(레퍼런스 지시 3.3).
   */
  eyebrow?: string;
  /**
   * 비주얼 위 오른쪽 ⋯ 버튼. 7.2-B의 차단 · 신고 메뉴를 연다.
   *
   * 노드가 아니라 콜백으로 받는다. 어두운 면 위에 놓이는 버튼이라 반투명 검정 + 흰
   * 아이콘이 고정인데, 화면이 `IconButton`을 직접 넘기면 그 규칙을 화면마다 다시
   * 맞춰야 한다.
   */
  onMore?: () => void;
  /** 이름 아래에 붙는 것. 보통 StatRow다 */
  children?: ReactNode;
};

export default function ProfileHero({ nickname, eyebrow, onMore, children }: Props) {
  const navigate = useNavigate();

  return (
    <div className={styles.hero}>
      <div className={styles.nav}>
        <button type="button" className={styles.navButton} onClick={() => navigate(-1)}>
          <ChevronLeftIcon />
          <VisuallyHidden>뒤로 가기</VisuallyHidden>
        </button>

        {eyebrow ? <span className={styles.eyebrow}>{eyebrow}</span> : <span />}

        {onMore ? (
          <button type="button" className={styles.navButton} onClick={onMore}>
            <MoreVerticalIcon />
            <VisuallyHidden>더 보기</VisuallyHidden>
          </button>
        ) : (
          <span className={styles.navSpacer} />
        )}
      </div>

      <div className={styles.content}>
        {/* 이 화면의 제목이다. 레퍼런스에서도 이름이 가장 큰 글자다 */}
        <h1 className={styles.name}>{nickname}</h1>

        {children ? <div className={styles.extra}>{children}</div> : null}
      </div>
    </div>
  );
}
