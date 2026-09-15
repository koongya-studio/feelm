/*
 * OTT 기준정보 목업. API-025 OTT 목록 조회
 *
 * **화면은 더 이상 이 목록을 보지 않는다.** `api/otts.ts`가 API-025를 실제로 부르므로
 * (업무 S15P21E106-379) 여기 남은 것은 아직 연동하지 않은 도메인의 목업이 OTT 이름을
 * 찾을 때 쓰는 기준표다 — 감상 기록(`mocks/watch.ts`) · 추천(`api/recommendations.ts`) ·
 * OTT 구독 추천(`mocks/ottRecommendations.ts`).
 *
 * 지원 OTT는 다섯 개로 확정됐다(API-025 처리 규칙 · 업무 S15P21E106-155).
 * 와이어프레임 1.2를 근거로 담아 두었던 쿠팡플레이 · 애플 TV+는 목록에 없고, 대신
 * 왓챠가 있다.
 *
 * `ottId`와 `logoUrl`은 새 환경의 Flyway seed
 * (`V202609142240__seed_approved_ott_catalog.sql`)와 같은 값이다. 그래야 실제 API-025가
 * 내려준 목록과 목업 데이터의 `ottId`가 같은 OTT를 가리킨다. 다만 **적재는 기존 환경의
 * 식별자를 보존하므로 이 순서가 어디서나 같다고 가정하면 안 된다**(API-025 처리 규칙).
 * 화면이 쓰는 값은 언제나 API-025 응답이다.
 */
import type { Ott } from '../types/ott';

const LOGO_BASE = 'https://image.tmdb.org/t/p/original';

export const mockOtts: Ott[] = [
  {
    ottId: 1,
    code: 'NETFLIX',
    name: '넷플릭스',
    logoUrl: `${LOGO_BASE}/rK1KljqmbvO9HQa1PBFLILWah72.png`,
  },
  {
    ottId: 2,
    code: 'DISNEY_PLUS',
    name: '디즈니+',
    logoUrl: `${LOGO_BASE}/5eZ872CghnHFLB1j8grszbrx0dx.png`,
  },
  {
    ottId: 3,
    code: 'WATCHA',
    name: '왓챠',
    logoUrl: `${LOGO_BASE}/ppQIS7UMtBwJSspSoGqDR8wO1Gr.png`,
  },
  {
    ottId: 4,
    code: 'WAVVE',
    name: '웨이브',
    logoUrl: `${LOGO_BASE}/mh1vBeclG7Eq15TjRnmyustqp3v.png`,
  },
  {
    ottId: 5,
    code: 'TVING',
    name: '티빙',
    logoUrl: `${LOGO_BASE}/w4ov8Zx73eu2TjHsDs5EmiuYKA4.png`,
  },
];
