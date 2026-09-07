/*
 * OTT 기준정보. API-025 OTT 목록 조회
 *
 * frontend-structure.md 8.1절의 타입 파일 목록에는 없던 파일이다. OTT 기준정보를
 * 온보딩 타입에 섞지 않기 위해 분리했다.
 */

export type Ott = {
  ottId: number;
  /** OTT 코드. 예: NETFLIX */
  code: string;
  /** 표시 이름. 예: 넷플릭스 */
  name: string;
  logoUrl: string | null;
};
