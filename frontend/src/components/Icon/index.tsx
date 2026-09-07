/*
 * 아이콘.
 * 와이어프레임(Feelm UI Wireframe Final.html)에 인라인 SVG로 그려진 아이콘을
 * 그대로 옮긴 것이다. 크기와 선 두께를 임의로 바꾸지 않는다.
 *
 * 색은 currentColor로 상속받아 부모의 상태(선택 · 비활성)를 따른다.
 * 와이어프레임이 일부 아이콘에 색을 직접 지정했으나, 색상 하드코딩을 하지 않는
 * 규칙(common-components.md 6.2)에 따라 currentColor로 바꿨다.
 *
 * 의미를 전달하는 아이콘은 부모 버튼이 접근 가능한 이름을 제공하므로
 * 여기서는 aria-hidden으로 숨긴다.
 */

const hidden = { 'aria-hidden': true, focusable: false } as const;

/** 하단 내비 — 홈 */
export function HomeIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" {...hidden}>
      <path
        d="M3 9l7-6 7 6v8h-5v-5H8v5H3V9z"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** 하단 내비 — 검색 */
export function SearchIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" {...hidden}>
      <circle cx="9" cy="9" r="5.5" stroke="currentColor" strokeWidth="1.5" />
      <path d="M13.5 13.5L17 17" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  );
}

/** 하단 내비 — 필름 */
export function FilmIcon() {
  return (
    <svg width="24" height="14" viewBox="0 0 24 14" fill="none" {...hidden}>
      <rect x="1" y="1" width="22" height="12" rx="2" stroke="currentColor" strokeWidth="1.3" />
      <path
        d="M4 3.5v1M4 9.5v1M8 3.5v1M8 9.5v1M12 3.5v1M12 9.5v1M16 3.5v1M16 9.5v1M20 3.5v1M20 9.5v1"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** 하단 내비 — 파티 */
export function PartyIcon() {
  return (
    <svg width="21" height="18" viewBox="0 0 21 18" fill="none" {...hidden}>
      <circle cx="7.5" cy="6" r="2.8" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="14.5" cy="7" r="2.2" stroke="currentColor" strokeWidth="1.5" />
      <path
        d="M2 16c.9-2.9 3-4.2 5.5-4.2S12.1 13.1 13 16M13.5 11.9c2 .1 3.9 1.2 4.7 3.6"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** 하단 내비 — MY */
export function MyIcon() {
  return (
    <svg width="18" height="19" viewBox="0 0 18 19" fill="none" {...hidden}>
      <circle cx="9" cy="5.5" r="3.4" stroke="currentColor" strokeWidth="1.5" />
      <path
        d="M2 17c1-3.4 3.7-4.8 7-4.8s6 1.4 7 4.8"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** 홈 헤더 — 알림 */
export function BellIcon() {
  return (
    <svg width="16" height="17" viewBox="0 0 16 17" fill="none" {...hidden}>
      <path
        d="M8 2a4.5 4.5 0 00-4.5 4.5c0 3.2-1.2 4.6-2 5.3h13c-.8-.7-2-2.1-2-5.3A4.5 4.5 0 008 2z"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
      <path d="M6.5 14.5a1.6 1.6 0 003 0" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  );
}

/** 화면 헤더 — 뒤로 가기 */
export function ChevronLeftIcon() {
  return (
    <svg width="9" height="15" viewBox="0 0 9 15" fill="none" {...hidden}>
      <path
        d="M7.5 1.5L1.5 7.5l6 6"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
