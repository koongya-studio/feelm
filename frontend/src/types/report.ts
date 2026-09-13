/*
 * 취향 리포트 타입. API-059 내 리포트 목록 · API-060 리포트 상세 · API-061 이미지 카드
 * 대응 명세는 docs/api/report-trend.md이다. 셋 다 **2차 MVP**라 아직 구현되지 않았다.
 *
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3).
 * 와이어프레임 3.3 · 3.4 · 7.4가 더 그리는 값은 `types/unspecified.ts`의 `ReportDetailView`다.
 */

/** 리포트 생성 상태. `GENERATING`이면 상세가 `409 REPORT_GENERATING`이다 */
export type ReportStatus = 'GENERATING' | 'READY';

/** 반기. `H1` 상반기 · `H2` 하반기 */
export type ReportPeriod = { year: number; half: 'H1' | 'H2' };

/** API-059 `items[]` */
export type ReportSummary = {
  reportId: number;
  period: ReportPeriod;
  status: ReportStatus;
  title: string;
  createdAt: string;
};

/** API-059 응답. 페이지가 없다 */
export type ReportList = {
  items: ReportSummary[];
};

type ReportCount = { name: string; count: number };

/** API-060 응답 */
export type ReportDetail = {
  reportId: number;
  period: ReportPeriod;
  summary: { ratingCount: number; newFlavorCount: number; averageScore: number };
  topGenres: (ReportCount & { code: string })[];
  topActors: (ReportCount & { personId: number })[];
  topDirectors: (ReportCount & { personId: number })[];
  characterCard: {
    name: string;
    movieTitle: string;
    description: string;
    imageUrl: string | null;
  };
  flavorChanges: { flavorCode: string; before: number; after: number }[];
  createdAt: string;
};

/** API-061 응답. `downloadUrl`은 짧게만 유효하다 */
export type ReportImage = {
  imageId: number;
  format: 'PNG';
  downloadUrl: string;
  expiresAt: string;
};
