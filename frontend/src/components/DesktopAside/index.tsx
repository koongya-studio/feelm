/*
 * 페이지가 우측 보조 패널에 내용을 보내는 문 (업무 S15P21E106-782).
 *
 * 페이지는 자기 JSX 안에서 이렇게 쓴다.
 *
 * ```tsx
 * <DesktopAside>
 *   <MyPopcornSummary />
 * </DesktopAside>
 * ```
 *
 * **자리가 없으면 아무것도 그리지 않는다.** 1280px 미만, 그리고 셸 바깥의 전체화면 · 인증
 * 화면이 그렇다. 페이지가 폭을 따로 재거나 조건을 걸 필요가 없다 — 같은 JSX가 모바일에서는
 * 없는 것이 되고 데스크톱에서는 오른쪽에 나타난다.
 *
 * **모바일에서 중앙 본문에 같은 내용을 두려면 페이지가 따로 그린다.** 이 컴포넌트는 옮기는
 * 것이 아니라 보내는 것이라, 안에 넣은 내용은 좁은 폭에서 사라진다. 좁은 폭에도 남아야 하는
 * 내용은 본문에 두고 넓은 폭에서 감추는 쪽이 맞다 — 어느 쪽인지는 화면마다 다르므로 페이지
 * 업무(-783 ~ -786)가 정한다.
 */
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useDesktopAsideSlot } from '../../contexts/desktopAsideContext';

type Props = {
  children: ReactNode;
};

export default function DesktopAside({ children }: Props) {
  const slot = useDesktopAsideSlot();

  if (!slot) return null;

  return createPortal(children, slot);
}
