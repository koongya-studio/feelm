/*
 * 팝콘 맛 기준정보 목업. API-044 팝콘 맛 기준정보 조회
 *
 * ADR-0015의 K-Means 8개 취향 유형에 대응한다. **8종의 표시명과 색상은 API-044 미정
 * 사항이므로 여기 값은 잠정값이다.** 확정되면 이 파일만 교체한다. 화면은 `code`로
 * 매칭하고 표시명 · 색상을 하드코딩하지 않는다.
 *
 * 색상은 Accent color가 미확정이던 동안 무채색 8단계로 두었다(와이어프레임 3.2 · 5.8의
 * 팝콘 알 색). **2026-09-08 디자인 방향이 B안 「암실」로 확정되어 색을 채웠다.**
 *
 * 값은 Poster 자리표시자의 색조 8종과 같은 팔레트다. 앱 안에서 색이 두 세계로 갈리지
 * 않게 하려는 것이고, 새로 정한 색이 아니다. 맛과 색의 대응은 대표 키워드를 따랐다.
 *
 * 무채색 8단계는 명도 순서로 `sortOrder`를 읽을 수 있었지만, 8가지 맛이 한 통에 섞인
 * 화면에서는 서로 구별되는 게 우선이다. 순서와 비율은 범례의 숫자가 알려준다.
 *
 * 여전히 **잠정값이다.** 실제 색은 API-044가 내려주는 런타임 값을 쓴다. 화면은 `code`로
 * 매칭하고 표시명 · 색상을 하드코딩하지 않는다.
 */
import type { PopcornFlavor } from '../types/taste';

export const mockPopcornFlavors: PopcornFlavor[] = [
  {
    code: 'FLAVOR_CAPER',
    displayName: '케이퍼',
    color: '#605234', // 올리브 골드 — 한탕 · 금고
    representativeKeywords: ['한탕', '팀플레이', '반전'],
    sortOrder: 1,
  },
  {
    code: 'FLAVOR_THRILL',
    displayName: '스릴',
    color: '#3A4B58', // 심야 — 긴장 · 추적
    representativeKeywords: ['긴장', '추적', '반전'],
    sortOrder: 2,
  },
  {
    code: 'FLAVOR_NOIR',
    displayName: '느와르',
    color: '#4D4A44', // 잉크 — 도시 · 밤
    representativeKeywords: ['도시', '밤', '배신'],
    sortOrder: 3,
  },
  {
    code: 'FLAVOR_CRIME',
    displayName: '범죄',
    color: '#7A1E1E', // 옥스블러드 — 수사 · 조직
    representativeKeywords: ['수사', '조직', '추적'],
    sortOrder: 4,
  },
  {
    code: 'FLAVOR_ACTION',
    displayName: '액션',
    color: '#6B3D47', // 와인 — 속도 · 격투
    representativeKeywords: ['속도', '격투', '추격'],
    sortOrder: 5,
  },
  {
    code: 'FLAVOR_DRAMA',
    displayName: '드라마',
    color: '#5C4A41', // 세피아 — 관계 · 상실
    representativeKeywords: ['관계', '성장', '상실'],
    sortOrder: 6,
  },
  {
    code: 'FLAVOR_COMEDY',
    displayName: '코미디',
    color: '#524368', // 플럼 — 오해 · 소동
    representativeKeywords: ['오해', '말맛', '소동'],
    sortOrder: 7,
  },
  {
    code: 'FLAVOR_SF',
    displayName: 'SF',
    color: '#425B50', // 딥 그린 — 미래 · 세계관
    representativeKeywords: ['미래', '기술', '세계관'],
    sortOrder: 8,
  },
];
