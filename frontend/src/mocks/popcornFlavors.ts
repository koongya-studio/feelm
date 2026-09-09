/*
 * 팝콘 맛 기준정보 목업. API-044 팝콘 맛 기준정보 조회
 *
 * ADR-0015의 K-Means 8개 취향 유형에 대응한다. **8종의 표시명과 색상은 API-044 미정
 * 사항이므로 여기 값은 잠정값이다.** 확정되면 이 파일만 교체한다. 화면은 `code`로
 * 매칭하고 표시명 · 색상을 하드코딩하지 않는다.
 *
 * 색은 두 번 바뀌었다. 처음에는 Accent color가 미확정이라 무채색 8단계였고,
 * 2026-09-08 「암실」안이 확정되면서 어두운 8색이 됐다. 「암실」은 폐기됐고
 * (`docs/screens/design-concept.md` 폐기 사유), 그 색은 지금 팔레트에서 쓸 수 없다.
 *
 * 팝콘은 예외 없이 어두운 면(`--color-surface-dark` #1C1C1F) 위에만 놀라간다. 밝은
 * 배경에서는 8종 중 절반이 판별되지 않기 때문이다. 그래서 이 팔레트의 기준은
 * **#1C1C1F 대비 3:1 이상**이다. 「암실」 8색은 1.64~2.30:1로 전원 미달이었고,
 * 아래 8색은 7.59~15.05:1이다.
 *
 * 색만으로 맛을 구분하게 두지 않는다. 범례는 항상 점 · 이름 · 개수 세 가지를 함께
 * 보여준다. 색상만으로 읽혀야 할 자리가 없으므로 맛끼리 명도가 가까워도 된다.
 *
 * 여전히 **잠정값이다.** 실제 색은 API-044가 내려주는 런타임 값을 쓴다.
 */
import type { PopcornFlavor } from '../types/taste';

export const mockPopcornFlavors: PopcornFlavor[] = [
  {
    code: 'FLAVOR_CAPER',
    displayName: '케이퍼',
    color: '#F0D07C', // 체다 — 한탕 · 금고
    representativeKeywords: ['한탕', '팀플레이', '반전'],
    sortOrder: 1,
  },
  {
    code: 'FLAVOR_THRILL',
    displayName: '스릴',
    color: '#9FC4D8', // 소금사탕 — 긴장 · 추적
    representativeKeywords: ['긴장', '추적', '반전'],
    sortOrder: 2,
  },
  {
    code: 'FLAVOR_NOIR',
    displayName: '느와르',
    color: '#D8D2C4', // 쿠키앤크림 — 도시 · 밤
    representativeKeywords: ['도시', '밤', '배신'],
    sortOrder: 3,
  },
  {
    code: 'FLAVOR_CRIME',
    displayName: '범죄',
    color: '#ECAABB', // 딸기 — 수사 · 조직
    representativeKeywords: ['수사', '조직', '추적'],
    sortOrder: 4,
  },
  {
    code: 'FLAVOR_ACTION',
    displayName: '액션',
    color: '#E0A063', // 카라멜 — 속도 · 격투
    representativeKeywords: ['속도', '격투', '추격'],
    sortOrder: 5,
  },
  {
    code: 'FLAVOR_DRAMA',
    displayName: '드라마',
    color: '#FBF1CF', // 기본 — 관계 · 상실
    representativeKeywords: ['관계', '성장', '상실'],
    sortOrder: 6,
  },
  {
    code: 'FLAVOR_COMEDY',
    displayName: '코미디',
    color: '#D9C6EA', // 블루베리 — 오해 · 소동
    representativeKeywords: ['오해', '말맛', '소동'],
    sortOrder: 7,
  },
  {
    code: 'FLAVOR_SF',
    displayName: 'SF',
    color: '#A9D6C8', // 민트 — 미래 · 세계관
    representativeKeywords: ['미래', '기술', '세계관'],
    sortOrder: 8,
  },
];
