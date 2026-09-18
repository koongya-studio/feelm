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

/** 3.1 필름 헤더 — 알림 */
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

/** 2.1 참여 중인 파티 카드 — 더 보기 */
export function ChevronRightIcon() {
  return (
    <svg width="7" height="12" viewBox="0 0 7 12" fill="none" {...hidden}>
      <path d="M1 1l5 5-5 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

/** 5.8 멤버 카드 — 펼침 상태 */
export function ChevronUpIcon() {
  return (
    <svg width="12" height="7" viewBox="0 0 16 9" fill="none" {...hidden}>
      <path d="M1 8l7-7 7 7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

/** 5.8 멤버 카드 — 접힘 상태 */
export function ChevronDownIcon() {
  return (
    <svg width="12" height="7" viewBox="0 0 16 9" fill="none" {...hidden}>
      <path d="M1 1l7 7 7-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  );
}

/** 2.1 참여 중인 파티 카드 — 파티 */
export function PartyGroupIcon() {
  return (
    <svg width="20" height="16" viewBox="0 0 20 16" fill="none" {...hidden}>
      <circle cx="7" cy="5" r="2.6" stroke="currentColor" strokeWidth="1.4" />
      <circle cx="13.5" cy="6" r="2.1" stroke="currentColor" strokeWidth="1.4" />
      <path
        d="M2 14c.8-2.6 2.8-3.7 5-3.7s4.2 1.1 5 3.7M12.5 10.6c1.9.1 3.7 1.1 4.4 3.4"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** 2.3 카드 메뉴 — OTT에서 바로 보기 */
export function PlayIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" {...hidden}>
      <path
        d="M4.5 2.8v8.4L11.5 7 4.5 2.8z"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** 2.3 카드 메뉴 — 영화 상세 보기 */
export function LinkIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" {...hidden}>
      <path
        d="M5.5 8.5l3-3M6 3.8l1.2-1.2a2.3 2.3 0 013.2 3.2L9.2 7M8 10.2l-1.2 1.2a2.3 2.3 0 01-3.2-3.2L4.8 7"
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** 1.2 OTT 선택 — 선택됨 */
export function CheckIcon() {
  return (
    <svg width="11" height="9" viewBox="0 0 32 24" fill="none" {...hidden}>
      <path
        d="M3 12.5L12 21 29 3"
        stroke="currentColor"
        strokeWidth="5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** 1.1 취향 배치 영역 중앙 — 나 */
export function PersonIcon() {
  return (
    <svg width="30" height="32" viewBox="0 0 34 36" fill="none" {...hidden}>
      <circle cx="17" cy="11" r="7" stroke="currentColor" strokeWidth="2" />
      <path
        d="M3 34c2-8 7.5-11.5 14-11.5S29 26 31 34"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** 2.4 영화 상세 — 더 보기 */
export function MoreVerticalIcon() {
  return (
    <svg width="4" height="16" viewBox="0 0 4 16" fill="currentColor" {...hidden}>
      <circle cx="2" cy="2" r="1.8" />
      <circle cx="2" cy="8" r="1.8" />
      <circle cx="2" cy="14" r="1.8" />
    </svg>
  );
}

/** 2.4 영화 상세 — OTT 링크로 이동 */
export function ExternalLinkIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 13 13" fill="none" {...hidden}>
      <path
        d="M5 1H2a1 1 0 00-1 1v9a1 1 0 001 1h9a1 1 0 001-1V8M8 1h4m0 0v4m0-4L5.5 7.5"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** 2.6 필름 추가 중 — 새 프레임 표식 */
export function HeartIcon() {
  return (
    <svg width="12" height="11" viewBox="0 0 30 27" fill="none" {...hidden}>
      <path
        d="M15 25S2 17 2 8.5C2 4.4 5.2 2 8.5 2c2.7 0 5 1.6 6.5 4C16.5 3.6 18.8 2 21.5 2 24.8 2 28 4.4 28 8.5 28 17 15 25 15 25z"
        fill="currentColor"
      />
    </svg>
  );
}

/**
 * 2.7 필름 추가 완료 — 결과 카드 표식.
 * 필름 헤더의 BellIcon과 모양이 비슷하지만 와이어프레임의 다른 그림이라 따로 둔다.
 */
export function ResultBellIcon() {
  return (
    <svg width="20" height="22" viewBox="0 0 18 20" fill="none" {...hidden}>
      <path
        d="M9 1.5c-3.6 0-6 2.7-6 6v4L1 15h16l-2-3.5v-4c0-3.3-2.4-6-6-6z"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <path d="M7 17.5a2 2 0 004 0" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

/** 2.3 카드 메뉴 — 관심없음 */
export function BlockIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 14 14" fill="none" {...hidden}>
      <circle cx="7" cy="7" r="5.5" stroke="currentColor" strokeWidth="1.3" />
      <path d="M3.2 3.2l7.6 7.6" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}

/*
 * 7.1 MY 헤더 — 설정.
 *
 * **와이어프레임에 없는 아이콘이다**(사용자 지시 2026-09-16). 7.1 헤더의 `설정` 글자를
 * 아이콘으로 바꾸면서 그렸다. 다른 아이콘과 같은 선 두께 · 둥근 끝으로 맞췄다.
 * 톱니를 둘레 모양으로 깎으면 20px에서 뭉개지므로, 테두리 원 밖으로 **굵고 짧은 선 여덟**을
 * 내어 톱니로 읽히게 한다. 처음에 선을 가늘고 길게 뒀더니 밝기 조절 아이콘으로 보였다.
 */
export function SettingsIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" {...hidden}>
      {/* 톱니 여덟. 굵고 짧게 둬서 햇살이 아니라 톱니로 읽히게 한다 */}
      <path
        d="M10 3.2V1.6M10 16.8v1.6M3.2 10H1.6M16.8 10h1.6M15.2 4.8l1.1-1.1M4.8 15.2l-1.1 1.1M15.2 15.2l1.1 1.1M4.8 4.8L3.7 3.7"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
      <circle cx="10" cy="10" r="6" stroke="currentColor" strokeWidth="1.4" />
      <circle cx="10" cy="10" r="2.3" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  );
}

/** 3.4 · 7.4 취향 리포트 — 공유하기 (업무 -308. 와이어프레임 헤더 오른쪽 아이콘) */
export function ShareIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" {...hidden}>
      <path
        d="M8 10V2M5 4.8L8 2l3 2.8M3 8.5v4.2c0 .7.6 1.3 1.3 1.3h7.4c.7 0 1.3-.6 1.3-1.3V8.5"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** 4.1 검색창 — 입력 지우기 (업무 -612). 채운 원 위에 X를 벽 색으로 뚫는다 */
export function ClearIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" {...hidden}>
      <circle cx="9" cy="9" r="8" fill="currentColor" />
      <path
        d="M6.2 6.2l5.6 5.6M11.8 6.2l-5.6 5.6"
        stroke="var(--color-surface-raised)"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
    </svg>
  );
}

/** 4.1 검색창 오른쪽 — 필터 (업무 -612). 손잡이 자리는 선을 끊어 원이 위에 놓인 것처럼 보인다 */
export function FilterIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" {...hidden}>
      <path
        d="M3 6h6.3M15.7 6H17M3 14h1.3M9.7 14H17"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
      <circle cx="12.5" cy="6" r="2.2" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="6.5" cy="14" r="2.2" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

/** 7.2 편집 — 항목 수정 (업무 -616). 구역 오른쪽 위에 같은 자리로 선다 */
export function PencilIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" {...hidden}>
      <path
        d="M11.8 3.2l3 3L6.4 14.6l-3.7.7.7-3.7 8.4-8.4zM10.3 4.7l3 3"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** 7.2 편집 — 프로필 사진 변경 (업무 -616) */
export function CameraIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" {...hidden}>
      <path
        d="M2.5 6.2c0-.8.6-1.4 1.4-1.4h1.9l1.2-1.8h4l1.2 1.8h1.9c.8 0 1.4.6 1.4 1.4v7.4c0 .8-.6 1.4-1.4 1.4H3.9c-.8 0-1.4-.6-1.4-1.4V6.2z"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      <circle cx="9" cy="9.6" r="2.7" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

/** 7.2-A 미리보기 안내 바 (업무 -616) */
export function InfoIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" {...hidden}>
      <circle cx="9" cy="9" r="8" fill="currentColor" />
      <path d="M9 8v5" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" />
      <circle cx="9" cy="5.3" r="1.1" fill="#fff" />
    </svg>
  );
}

/** 7.2-A · 7.2-B 공개 한줄평이 없을 때 (업무 -616) */
export function SpeechBubbleIcon() {
  return (
    <svg width="32" height="30" viewBox="0 0 32 30" fill="none" {...hidden}>
      <path
        d="M16 3C8.8 3 3 7.8 3 13.7c0 3.4 1.9 6.4 4.9 8.4L7 27l5.6-3c1.1.3 2.2.4 3.4.4 7.2 0 13-4.8 13-10.7S23.2 3 16 3z"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <circle cx="10.5" cy="13.7" r="1.4" fill="currentColor" />
      <circle cx="16" cy="13.7" r="1.4" fill="currentColor" />
      <circle cx="21.5" cy="13.7" r="1.4" fill="currentColor" />
    </svg>
  );
}
