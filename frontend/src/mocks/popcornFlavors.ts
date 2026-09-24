/*
 * 팝콘 맛 기준정보 목업. API-044 팝콘 맛 기준정보 조회
 *
 * S15P21E106-439의 상위 taste_id 0~7과 API-044의 FLAVOR_A~H 계약에 대응한다.
 * 화면은 `code`로 매칭하고 표시명 · 색상을 컴포넌트에 하드코딩하지 않는다.
 *
 * 색상과 스프라이트 순서는 기존 3D 팝콘 8종 자산을 A~H에 순서대로 연결한다. 표시명과 색상은
 * 나중에 바꿀 수 있지만 불변 코드는 바꾸지 않는다. 실제 화면은 API 응답을 우선 사용한다.
 *
 * 색만으로 맛을 구분하게 두지 않는다. 범례는 항상 알 · 이름 · 개수를 함께 보여준다.
 * 그래서 맛끼리 명도가 가까워도 된다. 밝은 판 위에서는 알에 테두리를 둘러 윤곽을
 * 살린다(`PopcornBucketPage.module.css`의 `.kernel` 참고).
 */
import type { PopcornFlavor } from '../types/taste';

export const mockPopcornFlavors: PopcornFlavor[] = [
  {
    code: 'FLAVOR_A',
    displayName: '오리지널 솔트',
    color: '#F3DFB2', // 아이보리 알맹이, 버터빛 속살
    description: '있었던 일을 그대로 담은 이야기에 끌려요',
    sortOrder: 1,
  },
  {
    code: 'FLAVOR_B',
    displayName: '다크초코',
    color: '#5A3623', // 윗부분만 다크 초콜릿에 담근 초코송이 스타일
    description: '서늘하고 낯선 세계를 즐겨요',
    sortOrder: 2,
  },
  {
    code: 'FLAVOR_C',
    displayName: '솔티카라멜',
    color: '#CF8F4A', // 윤기 나는 카라멜 글레이즈와 소금 결정
    description: '웃기면서 씁쓸한 관계 이야기를 봐요',
    sortOrder: 3,
  },
  {
    code: 'FLAVOR_D',
    displayName: '피스타치오',
    color: '#D6CF9C', // 연두색 피스타치오 코팅
    description: '사람과 시대를 깊게 들여다봐요',
    sortOrder: 4,
  },
  {
    code: 'FLAVOR_E',
    displayName: '쿠키앤크림',
    color: '#F0E7D3', // 크림 코팅에 박힌 쿠키 조각
    description: '쫓고 쫓기는 긴장감을 즐겨요',
    sortOrder: 5,
  },
  {
    code: 'FLAVOR_F',
    displayName: '체다치즈',
    color: '#E6AA52', // 보송한 체다 파우더, 깊은 속살만 크림색
    description: '가볍게 웃고 기분 좋아지는 영화를 봐요',
    sortOrder: 6,
  },
  {
    code: 'FLAVOR_G',
    displayName: '스트로베리',
    color: '#E7A79E', // 딸기 코팅과 과육 조각
    description: '상상으로 그린 세계를 여행해요',
    sortOrder: 7,
  },
  {
    code: 'FLAVOR_H',
    displayName: '블루베리',
    color: '#B78A96', // 블루베리 스월과 마카롱 조각
    description: '크고 시원한 모험에 빠져요',
    sortOrder: 8,
  },
];
