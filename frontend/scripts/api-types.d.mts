/*
 * `api-types.mjs`의 타입. `vite.config.ts`가 이 스크립트를 가져다 쓰므로 필요하다.
 *
 * 스크립트를 `.ts`로 두지 않은 것은 `npm run api:generate`가 Node로 바로 실행하기
 * 때문이다(CI Node 20에는 TypeScript 실행기가 없다).
 */

/** OpenAPI 스냅샷에서 만든 `api-schema.d.ts`의 원문. 파일에 쓰는 것과 검사에 쓰는 것이 같다 */
export function generate(): Promise<string>;
