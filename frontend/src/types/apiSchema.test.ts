/*
 * 백엔드 OpenAPI 스냅샷과 생성 타입이 쓸모 있는 상태인지 본다 (업무 S15P21E106-777).
 *
 * **생성 타입이 스냅샷과 같은지는 여기서 보지 않는다.** 그 검사는 `vite build`의
 * 플러그인(`vite.config.ts`)과 `npm run api:check`가 한다 — 생성기를 돌리려면 파일 경로를
 * 읽어야 하는데 vitest 안에서는 모듈 주소가 파일 주소가 아니다. 프론트 MR Job이 돌리는 고정
 * 명령에 `vite build`가 들어 있어(`infra/scripts/ci-frontend-mr-runner.py`) MR에서도 걸린다.
 *
 * 스냅샷 자체가 백엔드와 같은지는 프론트가 알 수 없다. 백엔드의 `OpenApiSnapshotTest`가 본다.
 *
 * 여기서 지키는 것은 **입력과 출력이 비어 있지 않다는 것**이다. 스냅샷이 빈 문서로 갈리거나
 * 실연동 경로가 빠지면 위의 두 검사는 통과하면서(생성 결과도 함께 비므로) 타입은 아무것도
 * 지키지 못하게 된다.
 */
import { describe, expect, it } from 'vitest';
import schema from '../generated/openapi.json';
import generated from '../generated/api-schema.d.ts?raw';

/** 업무 -777이 이름을 든 실연동 세 곳. 추천 · 파티 추천 · OTT 추천이다 */
const CONNECTED_PATHS = [
  '/api/v1/recommendations',
  '/api/v1/parties/{partyId}/recommendations',
  '/api/v1/recommendations/otts',
];

describe('백엔드 OpenAPI 스냅샷', () => {
  it('springdoc 문서의 모양을 갖추고 경로가 들어 있다', () => {
    expect(schema.openapi).toMatch(/^3\./);
    expect(Object.keys(schema.paths).length).toBeGreaterThan(0);
  });

  it.each(CONNECTED_PATHS)('실연동 경로 %s 가 스냅샷에 있다', (path) => {
    expect(Object.keys(schema.paths)).toContain(path);
  });
});

describe('생성 API 타입', () => {
  it('직접 고치지 말라는 말을 머리에 달고 있다', () => {
    expect(generated).toContain('직접 고치지 않는다');
  });

  it.each(CONNECTED_PATHS)('실연동 경로 %s 가 생성 타입에 있다', (path) => {
    expect(generated).toContain(`"${path}"`);
  });
});
