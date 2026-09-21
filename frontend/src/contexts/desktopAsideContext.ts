/*
 * 우측 보조 패널 자리 (업무 S15P21E106-782).
 *
 * 데스크톱 셸은 중앙 본문 오른쪽에 보조 패널 자리를 연다. **그 자리를 셸이 채우지는 않는다** —
 * 2026-09-21 시안 5종의 우측 카드는 화면마다 다르다(취향 팝콘 · 추천 기준 · 우리 영화 모임 ·
 * 영화 상세 · 취향 요약). 무엇을 담을지는 페이지 업무(-783 ~ -786)가 정한다.
 *
 * 그래서 셸은 빈 자리와 그 자리를 가리키는 요소만 내놓고, 페이지는 `components/DesktopAside`로
 * 자기 내용을 그 자리에 보낸다. 라우터가 `Outlet`으로 페이지를 본문 안쪽에 그리므로 페이지가
 * 자기 JSX로는 본문 바깥에 닿을 수 없다 — 그 거리를 포털이 건넌다.
 *
 * 값이 `null`이면 자리가 없다는 뜻이다. 1280px 미만이거나 셸 바깥(전체화면 · 인증 화면)이다.
 */
import { createContext, useContext } from 'react';

export const DesktopAsideContext = createContext<HTMLElement | null>(null);

/** 보조 패널 자리. 없으면 `null`이고, 그때 `DesktopAside`는 아무것도 그리지 않는다 */
export function useDesktopAsideSlot(): HTMLElement | null {
  return useContext(DesktopAsideContext);
}
