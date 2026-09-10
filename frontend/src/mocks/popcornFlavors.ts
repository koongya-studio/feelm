/*
 * 팝콘 맛 기준정보 목업. API-044 팝콘 맛 기준정보 조회
 *
 * ADR-0015의 K-Means 8개 취향 유형에 대응한다. **8종의 표시명과 색상은 API-044 미정
 * 사항이므로 여기 값은 전부 잠정값이다.** 확정되면 이 파일만 교체한다. 화면은 `code`로
 * 매칭하고 표시명 · 색상을 하드코딩하지 않는다.
 *
 * 값이 세 번 바뀌었다. 처음에는 Accent color가 미확정이라 무채색 8단계였고, 2026-09-08
 * 「암실」안이 확정되면서 어두운 8색이 됐다. 「암실」은 폐기됐다
 * (`docs/screens/design-concept.md` 폐기 사유).
 *
 * **2026-09-10, 디자인이 3D로 만든 실제 팝콘 8종에 맞췄다.** 이름과 색을 디자인
 * 파일에서 그대로 옮겼고, 같은 모델을 렌더한 스프라이트가 `src/assets/popcorn`에 있다.
 * 그전까지 여기 있던 장르명(케이퍼 · 스릴 · 느와르 …)은 3D 팝콘이 나오기 전에 코드에서
 * 임시로 붙여 둔 이름이었다. 지금 이름도 마찬가지로 임시다. 팀이 맛 이름을 확정하거나
 * API-044가 값을 내려주면 그때 갱신한다.
 *
 * 색만으로 맛을 구분하게 두지 않는다. 범례는 항상 알 · 이름 · 개수를 함께 보여준다.
 * 그래서 맛끼리 명도가 가까워도 된다. 밝은 판 위에서는 알에 테두리를 둘러 윤곽을
 * 살린다(`PopcornBucketPage.module.css`의 `.kernel` 참고).
 */
import type { PopcornFlavor } from '../types/taste';

export const mockPopcornFlavors: PopcornFlavor[] = [
  {
    code: 'FLAVOR_CAPER',
    displayName: '기본',
    color: '#fbf1cf', // 아이보리 알맹이, 버터빛 속살
    representativeKeywords: ['담백', '기본', '고소'],
    sortOrder: 1,
  },
  {
    code: 'FLAVOR_THRILL',
    displayName: '솔티드 카라멜',
    color: '#dc9a4e', // 윤기 나는 카라멜 글레이즈와 소금 결정
    representativeKeywords: ['달콤', '짭짤', '카라멜'],
    sortOrder: 2,
  },
  {
    code: 'FLAVOR_NOIR',
    displayName: '체다 치즈',
    color: '#f0d07c', // 보송한 체다 파우더, 깊은 속살만 크림색
    representativeKeywords: ['치즈', '고소', '진한맛'],
    sortOrder: 3,
  },
  {
    code: 'FLAVOR_CRIME',
    displayName: '더블 초콜릿',
    color: '#5c3020', // 윗부분만 다크 초콜릿에 담근 초코송이 스타일
    representativeKeywords: ['초콜릿', '진한맛', '달콤'],
    sortOrder: 4,
  },
  {
    code: 'FLAVOR_ACTION',
    displayName: '쿠키 앤 크림',
    color: '#f4f1ea', // 크림 코팅에 박힌 쿠키 조각
    representativeKeywords: ['쿠키', '크림', '달콤'],
    sortOrder: 5,
  },
  {
    code: 'FLAVOR_DRAMA',
    displayName: '비스코프 에스프레소 크런치',
    color: '#c48c50', // 비스킷 크럼과 에스프레소 스월
    representativeKeywords: ['커피', '비스킷', '쌉싸름'],
    sortOrder: 6,
  },
  {
    code: 'FLAVOR_COMEDY',
    displayName: '사랑에 빠진 딸기',
    color: '#ecaabb', // 딸기 코팅과 과육 조각
    representativeKeywords: ['딸기', '상큼', '달콤'],
    sortOrder: 7,
  },
  {
    code: 'FLAVOR_SF',
    displayName: '블루베리 마카롱',
    color: '#d9c6ea', // 블루베리 스월과 마카롱 조각
    representativeKeywords: ['블루베리', '마카롱', '상큼'],
    sortOrder: 8,
  },
];
