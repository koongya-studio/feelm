/*
 * 취향 리포트 타입. API-059 내 리포트 목록 · API-060 리포트 상세 · API-080 첫 발급 기록 ·
 * API-061 이미지 카드. 대응 명세는 docs/api/report-trend.md이다.
 *
 * 059 · 060 · 080은 2026-09-21에 백엔드가 생겼다(업무 -556). **061 이미지 카드만 아직 없어**
 * `api/reports.ts`의 `createReportImage`가 목업으로 남는다(업무 -731).
 *
 * 계약의 사본이며 계약에 없는 필드를 넣지 않는다(frontend-structure.md 8.3). 와이어프레임 3.3 ·
 * 3.4 · 7.4가 그리던 4컷 · 국가 · 시대 비율 · 지난 반기 비교 · 발급 요약은 같은 날 계약으로
 * 들어와(`docs/api/report-trend.md` API-060) `types/unspecified.ts`에서 이 파일로 옮겼다.
 */

/** 리포트 생성 상태. `GENERATING`이면 상세가 `409 REPORT_GENERATING`이다 */
export type ReportStatus = 'GENERATING' | 'READY';

/** 반기. `H1` 상반기 · `H2` 하반기 */
export type ReportPeriod = { year: number; half: 'H1' | 'H2' };

/** API-059 `items[]`. 발급했거나 다음 반기 공개 시각이 지난 `READY`만 온다 */
export type ReportSummary = {
  reportId: number;
  period: ReportPeriod;
  status: ReportStatus;
  title: string;
  createdAt: string;
};

/**
 * API-059 `current`. 공개 시각이 지난 가장 최근 반기 한 행이다.
 *
 * `status`가 `GENERATING`이면 아직 만드는 중이라 목록 카드가 아니라 이 자리에서만 알린다.
 * `issuedAt`이 null인 `READY`가 홈 · 필름의 `새 리포트 받기` 대상이다(API-080).
 */
export type ReportCurrent = {
  reportId: number;
  period: ReportPeriod;
  status: ReportStatus;
  issuedAt: string | null;
};

/** API-059 응답. 페이지가 없다 */
export type ReportList = {
  items: ReportSummary[];
  current: ReportCurrent | null;
  /** 최신 공개 반기가 아직 준비 중이거나 준비 기록조차 없으면 참이다 */
  preparing: boolean;
};

type ReportCount = { name: string; count: number };

/**
 * 4컷 한 칸의 사진. `url`은 원본, `cutoutUrl`은 배경을 지운 이미지다.
 *
 * 감독 · 배우 칸은 `people.profile_url`을 그대로 준다. 장르 · 캐릭터 칸의 출처와 누끼 ·
 * 크롭은 계약의 미정 사항이라 지금은 늘 `null`이다.
 */
export type ReportCutImage = {
  url: string | null;
  cutoutUrl?: string | null;
  /** 0~100 범위의 크롭 위치 */
  position?: { x: number; y: number };
  zoom?: number;
};

/**
 * 4컷 한 칸의 이름. `name`은 표시 이름(한글)이고 `displayName`은 크게 쓰는 표기다.
 * `displayName`은 없으면 `null`이며 화면은 `name`을 쓴다. 영문 표기의 출처는 계약의 미정 사항이다.
 */
export type ReportCutName = { name: string; displayName?: string | null };

/**
 * API-060 `fourCut`. 네 칸의 이름은 `topGenres[0]` · `topDirectors[0]` · `topActors[0]` ·
 * `characterCard`와 같다(사용자 시안 2026-09-15, 업무 -321).
 *
 * **선정 결과가 없는 칸은 카드째로 null이다.** 화면은 레이아웃을 그대로 두고 그 칸만
 * `준비 중`으로 그린다.
 *
 * **`character`에는 `image`가 없다.** 캐릭터 카드가 고정 16종이라 04 칸 그림은 저장소
 * 에셋이고 화면이 `characterCard.code`로 짝짓는다(업무 -618). 계약도 이 칸의 이미지 URL을
 * 내리지 않는다. 백엔드 구현(업무 -779) 전까지는 `character`가 계속 null이다.
 */
export type ReportFourCut = {
  genre: (ReportCutName & { image: ReportCutImage }) | null;
  director: (ReportCutName & { image: ReportCutImage; work: ReportCutImage | null }) | null;
  actor: (ReportCutName & { image: ReportCutImage }) | null;
  character: ReportCutName | null;
};

/** 3.4 · 7.4 비율 한 줄. `share`는 화면에 쓰는 0~100 정수 퍼센트다 */
export type ReportShare = { label: string; share: number };

/**
 * 3.4 · 7.4 `지난 반기와 비교` 한 줄. `delta`는 퍼센트포인트 차이이고
 * `isNew`는 이번 반기 전 필름에 없던 항목이다.
 */
export type ReportComparison = { label: string; delta: number; isNew: boolean };

/**
 * 3.4 · 7.4 `취향 키워드` 한 개.
 *
 * 이번 리포트 구현 범위에서는 분석을 하지 않아 계약이 빈 배열을 주고 화면은 이 칸을 숨긴다.
 */
export type ReportKeyword = { code: string; label: string; rationale: string };

/**
 * `characterCard.axes` 한 축. 순서는 코드의 글자 순서와 같다.
 *
 * 화면은 이걸로 네 축 막대를 그려 **왜 이 카드인지**를 보인다. 판정이 그 반기 대상자 전체의
 * 중앙값 기준 상대 판정이라 반기마다 바뀔 수 있는데, 축을 같이 보이면 그 점이 설명된다.
 */
export type ReportCharacterAxis = {
  axis: 'EXPLORATION' | 'TONE' | 'STANCE' | 'VOLUME';
  /** 그 축의 판정 결과 한 글자. `E`/`R` · `L`/`D` · `H`/`M` · `V`/`S` */
  value: string;
  /** 사용자에게 보이는 축 값 이름. `탐험` · `그림자` 같은 말이다 */
  label: string;
};

/**
 * API-060 `characterCard`. 고정 16종 중 하나다(업무 -618).
 *
 * **계약은 `code`뿐이다.** `name` · `tagline` · `description`은 바뀔 수 있는 기준정보라
 * 서버가 주는 값을 그대로 쓰고 화면이 따로 갖지 않는다. 04 칸 그림만 저장소에 있어
 * `code`로 짝짓는다(`components/ReportFourCut/characterCards.ts`).
 */
export type ReportCharacterCard = {
  /** `ELHV` 형태의 4글자. 불변 계약이며 사용자에게도 보인다 */
  code: string;
  /** 카드 이름. 보통명사다 */
  name: string;
  /** 한 줄 요약 */
  tagline: string;
  /** 네 축을 풀어 쓴 설명문 */
  description: string;
  axes: ReportCharacterAxis[];
  /** `frameCount`가 10 미만이면 `lowSample`이 true이고 화면이 기준 편수를 함께 알린다 */
  basis: { frameCount: number; lowSample: boolean };
};

/** API-060 응답 */
export type ReportDetail = {
  reportId: number;
  period: ReportPeriod;
  summary: { ratingCount: number; newFlavorCount: number; averageScore: number };
  topGenres: (ReportCount & { code: string })[];
  topActors: (ReportCount & { personId: number })[];
  topDirectors: (ReportCount & { personId: number })[];
  /**
   * 고정 16종 중 하나(업무 -618). 백엔드 구현(업무 -779) 전까지는 null이고 화면은 캐릭터
   * 설명을 숨긴다. 구현 뒤에는 리포트가 생기면 늘 값이 있다 — 16종을 다 쓰므로 후보 없음이
   * 생기지 않는다.
   */
  characterCard: ReportCharacterCard | null;
  flavorChanges: { flavorCode: string; before: number; after: number }[];
  createdAt: string;
  fourCut: ReportFourCut;
  genreShares: ReportShare[];
  countryShares: ReportShare[];
  eraShares: ReportShare[];
  keywords: ReportKeyword[];
  comparisons: ReportComparison[];
  /** 3.3 인화 화면의 요약. 최대 세 줄이다 */
  highlights: string[];
};

/** API-080 응답. `issuedAt`은 최초 발급 시각이며 재요청에도 바뀌지 않는다 */
export type ReportIssue = {
  reportId: number;
  issuedAt: string;
};

/** API-061 응답. `downloadUrl`은 짧게만 유효하다 */
export type ReportImage = {
  imageId: number;
  format: 'PNG';
  downloadUrl: string;
  expiresAt: string;
};
