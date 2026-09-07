/*
 * 공통 규약의 상태값.
 * 값은 계약(docs/api/README.md 공통 규약) 그대로 UPPER_SNAKE_CASE 문자열이다.
 *
 * 이 파일에는 실제로 쓰는 상태값만 둔다. 나머지는 해당 화면 업무에서 추가한다.
 */

/** 추천 유형. API-045 · API-047 */
export type RecommendationType = 'TASTE' | 'DISCOVERY' | 'PARTY';

/**
 * 감상 기록 상태. API-033 · API-035 · API-036
 * `LINK_CLICKED` OTT 링크 클릭(감상 확정 아님) → `WATCHED_UNRATED` 감상 완료, 평가 전
 * → `RATED` 평가 완료. 미응답 7일이면 서버가 `EXPIRED`로 바꾼다.
 */
export type WatchStatus = 'LINK_CLICKED' | 'WATCHED_UNRATED' | 'RATED' | 'EXPIRED';

/** 공개 범위. API-036 · API-037. 공통 규약 기본값은 `PRIVATE` */
export type Visibility = 'PUBLIC' | 'PRIVATE';
