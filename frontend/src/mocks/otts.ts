/*
 * OTT 기준정보 목업. API-025 OTT 목록 조회
 *
 * 지원 OTT 목록은 API-025 미정 사항이다. 와이어프레임 1.2가 6개를 보여주므로 그 6개를
 * 둔다(넷플릭스 · 티빙 · 웨이브 · 디즈니+ · 쿠팡플레이 · 애플 TV+). 확정되면 이 파일을
 * 교체한다.
 *
 * 와이어프레임의 배지 글자는 계약 필드가 아니므로 여기 두지 않고
 * src/utils/ott.ts에서 코드로부터 만든다. 6개 모두 코드 첫 글자가 와이어프레임 표기와
 * 일치한다(N · T · W · D · C · A).
 */
import type { Ott } from '../types/ott';

export const mockOtts: Ott[] = [
  { ottId: 1, code: 'NETFLIX', name: '넷플릭스', logoUrl: null },
  { ottId: 2, code: 'TVING', name: '티빙', logoUrl: null },
  { ottId: 3, code: 'WAVVE', name: '웨이브', logoUrl: null },
  { ottId: 4, code: 'DISNEY_PLUS', name: '디즈니+', logoUrl: null },
  { ottId: 5, code: 'COUPANG_PLAY', name: '쿠팡플레이', logoUrl: null },
  { ottId: 6, code: 'APPLE_TV_PLUS', name: '애플 TV+', logoUrl: null },
];
