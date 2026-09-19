/*
 * 상세로 가는 포스터의 이동 단서(업무 -697).
 *
 * 포스터를 감싸고 오른쪽 아래에 작은 화살표를 얹는다. **누를 수 있는 포스터에만 쓴다** —
 * 읽기만 하는 포스터(2.4 영화 상세 · 5.9 파티 추천 · 6.4 OTT 결과)에는 단서가 없어야
 * 두 포스터가 기본 상태에서 갈린다.
 *
 * 링크 · 버튼 쪽에 `interaction.module.css`의 `.posterLink`를 붙여야 누름 상태가 산다.
 * 화살표는 장식이다. 이동한다는 말은 링크의 이름이 이미 한다.
 *
 * `cue={false}`면 화살표 없이 누름 상태만 받는다. 포스터 아래쪽을 다른 정보가 덮고 있어
 * 단서를 그 정보 안에 따로 두는 2.2 추천 카드가 쓴다.
 */
import type { ReactNode } from 'react';
import { ChevronRightIcon } from '../Icon';
import interaction from '../../styles/interaction.module.css';

type Props = {
  children: ReactNode;
  cue?: boolean;
};

export default function PosterCue({ children, cue = true }: Props) {
  return (
    <span className={interaction.posterBox}>
      {children}
      {cue ? (
        <span className={interaction.posterCue} aria-hidden="true" data-testid="poster-cue">
          <ChevronRightIcon />
        </span>
      ) : null}
    </span>
  );
}
