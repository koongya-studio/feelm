/*
 * 공통 규약의 상태값.
 * 값은 계약(docs/api/README.md 공통 규약) 그대로 UPPER_SNAKE_CASE 문자열이다.
 *
 * 이 파일에는 실제로 쓰는 상태값만 둔다. 나머지는 해당 화면 업무에서 추가한다.
 */

/** 추천 유형. API-045 · API-047 */
export type RecommendationType = 'TASTE' | 'DISCOVERY' | 'PARTY';
