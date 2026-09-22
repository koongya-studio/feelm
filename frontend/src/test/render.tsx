/*
 * 화면을 테스트에서 렌더하는 도우미 (업무 S15P21E106-333).
 *
 * 화면 컴포넌트는 대부분 `Link`나 `useNavigate`를 쓰므로 Router 없이 렌더하면
 * 그 자리에서 터진다. 여기서 `MemoryRouter`로 감싸 실제 브라우저 주소를 건드리지
 * 않고도 이동 경로를 확인할 수 있게 한다.
 *
 * 이동을 검증해야 하면 `location`을 읽는다. `useNavigate`를 가로채는 방법도 있지만,
 * 그러면 "무엇을 호출했는가"를 보게 되고 "어디로 갔는가"는 못 본다.
 *
 * ─────────────────────────── 주소 갱신을 기다리지 않는다 (업무 S15P21E106-789)
 *
 * `MemoryRouter`는 주소 상태를 `React.startTransition` 안에서 바꾼다(react-router 8의
 * `components.js`). 전환은 **낮은 우선순위**라 같은 순간의 보통 갱신보다 늦게 커밋될 수
 * 있고, 그 차이는 기계가 바쁠수록 벌어진다.
 *
 * 그래서 4.1 검색처럼 **조건을 주소에 적고 결과를 함께 그리는 화면**에서, 결과 머리줄을
 * 기다린 뒤 주소를 바로 읽으면 이전 주소가 잡히는 일이 있었다. 전체 실행에서만 간헐적으로
 * 깨지던 원인이다(S15P21E106-789).
 *
 * `useTransitions={false}`로 주소 갱신을 보통 갱신으로 되돌린다. 화면이 하는 일은 그대로이고
 * 커밋 시점만 결과와 같아진다 — 기다리는 단정문으로 감싸 덮는 대신 원인을 없앤다.
 */
import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { ReactElement, ReactNode } from 'react';

/** 현재 경로를 DOM에 노출한다. 이동 결과를 단정문으로 읽기 위한 것이다 */
function LocationProbe() {
  const location = useLocation();

  return <div data-testid="location">{location.pathname + location.search}</div>;
}

type Options = {
  /** 시작 주소. 기본값은 루트다 */
  route?: string;
  /**
   * 라우트 패턴. `useParams`를 쓰는 화면에 필요하다.
   *
   * 없으면 화면을 그대로 그린다 — 대부분의 화면은 파라미터가 없어 패턴이 필요 없고,
   * `Routes`로 감싸면 그만큼 실제 구조와 멀어진다. 업무 -304에서 `/parties/:partyId`
   * 형태의 화면이 생기며 추가했다.
   */
  path?: string;
};

export function renderWithRouter(ui: ReactElement, { route = '/', path }: Options = {}) {
  return render(
    <MemoryRouter initialEntries={[route]} useTransitions={false}>
      {path ? (
        <Routes>
          <Route path={path} element={ui} />
        </Routes>
      ) : (
        ui
      )}
      <LocationProbe />
    </MemoryRouter>,
  );
}

/** 레이아웃 없이 자식만 감싸야 할 때 */
export function withRouter(children: ReactNode, route = '/') {
  return (
    <MemoryRouter initialEntries={[route]} useTransitions={false}>
      {children}
    </MemoryRouter>
  );
}
