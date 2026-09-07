/*
 * 팝콘 맛 기준정보 목업. API-044 팝콘 맛 기준정보 조회
 *
 * ADR-0015의 K-Means 8개 취향 유형에 대응한다. **8종의 표시명과 색상은 API-044 미정
 * 사항이므로 여기 값은 잠정값이다.** 확정되면 이 파일만 교체한다. 화면은 `code`로
 * 매칭하고 표시명 · 색상을 하드코딩하지 않는다.
 *
 * 색상은 디자인 미확정(Accent color)과도 얽혀 있어 무채색 계열로 두었다. 실제 색은
 * API-044가 내려주는 런타임 값을 쓴다.
 */
import type { PopcornFlavor } from '../types/taste';

export const mockPopcornFlavors: PopcornFlavor[] = [
  {
    code: 'FLAVOR_CAPER',
    displayName: '케이퍼',
    color: '#3C3A36',
    representativeKeywords: ['한탕', '팀플레이', '반전'],
    sortOrder: 1,
  },
  {
    code: 'FLAVOR_THRILL',
    displayName: '스릴',
    color: '#5A574F',
    representativeKeywords: ['긴장', '추적', '반전'],
    sortOrder: 2,
  },
  {
    code: 'FLAVOR_NOIR',
    displayName: '느와르',
    color: '#6E6A61',
    representativeKeywords: ['도시', '밤', '배신'],
    sortOrder: 3,
  },
  {
    code: 'FLAVOR_CRIME',
    displayName: '범죄',
    color: '#807C72',
    representativeKeywords: ['수사', '조직', '추적'],
    sortOrder: 4,
  },
  {
    code: 'FLAVOR_ACTION',
    displayName: '액션',
    color: '#928D83',
    representativeKeywords: ['속도', '격투', '추격'],
    sortOrder: 5,
  },
  {
    code: 'FLAVOR_DRAMA',
    displayName: '드라마',
    color: '#A5A29B',
    representativeKeywords: ['관계', '성장', '상실'],
    sortOrder: 6,
  },
  {
    code: 'FLAVOR_COMEDY',
    displayName: '코미디',
    color: '#B8B5AE',
    representativeKeywords: ['오해', '말맛', '소동'],
    sortOrder: 7,
  },
  {
    code: 'FLAVOR_SF',
    displayName: 'SF',
    color: '#C7C4BE',
    representativeKeywords: ['미래', '기술', '세계관'],
    sortOrder: 8,
  },
];
