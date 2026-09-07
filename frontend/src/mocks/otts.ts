/*
 * OTT 기준정보 목업. API-025 OTT 목록 조회
 *
 * 지원 OTT 목록은 API-025 미정 사항이다. 여기에는 와이어프레임 화면에 실제로
 * 나타나는 4개만 둔다(2.1 · 2.2 · 2.3의 N · T · W · D). 확정되면 이 파일을 교체한다.
 *
 * 와이어프레임의 배지 글자는 계약 필드가 아니므로 여기 두지 않고
 * src/utils/ott.ts에서 코드로부터 만든다.
 */
import type { Ott } from '../types/ott';

export const mockOtts: Ott[] = [
  { ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null },
  { ottId: 2, code: 'TVING', name: '티빙', logoUrl: null },
  { ottId: 3, code: 'WAVVE', name: '웨이브', logoUrl: null },
  { ottId: 4, code: 'DISNEY_PLUS', name: '디즈니+', logoUrl: null },
];
