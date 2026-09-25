/*
 * 백엔드 OpenAPI 스냅샷에서 API 응답 타입을 만든다 (업무 S15P21E106-777).
 *
 * 입력은 `src/generated/openapi.json` 하나다. 이 파일은 백엔드가 springdoc으로 내놓는
 * 문서를 그대로 옮긴 것이고, 실제 문서와 같은지는 백엔드 테스트
 * (`OpenApiSnapshotTest`)가 지킨다. **여기서 서버에 접속하지 않는다** — 프론트 MR Job은
 * 네트워크가 막혀 있고 `frontend/`만 받는다(`infra/scripts/FRONTEND-MR-ISOLATION.md`).
 *
 * 쓰는 법
 * - `node scripts/api-types.mjs` — `src/generated/api-schema.d.ts`를 다시 쓴다
 * - `node scripts/api-types.mjs --check` — 다시 만든 결과가 커밋본과 다르면 1로 끝난다
 *
 * `--check`는 `vite.config.ts`의 플러그인이 빌드마다 부른다. 프론트 MR Job이 돌리는
 * 명령은 러너에 고정되어 있어(`ci-frontend-mr-runner.py`) 새 npm script를 파이프라인에
 * 끼워 넣을 수 없고, 고정 명령에 이미 있는 `vite build`가 유일한 자리다.
 */
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';

const SCHEMA = new URL('../src/generated/openapi.json', import.meta.url);
const OUTPUT = new URL('../src/generated/api-schema.d.ts', import.meta.url);

const BANNER = `/*
 * 백엔드 OpenAPI 스냅샷(\`openapi.json\`)에서 만든 파일이다. **직접 고치지 않는다.**
 *
 * 다시 만들기: \`npm run api:generate\`
 * 스냅샷 갱신: 백엔드에서 \`FEELM_OPENAPI_SNAPSHOT_WRITE=1 ./gradlew test --tests '*OpenApiSnapshotTest'\`
 *
 * 서버에 없는 필드를 여기에 손으로 더하지 않는다 — 화면용 값은 뷰 모델에서 따로 만든다
 * (\`frontend/docs/screens/frontend-structure.md\` 8.3).
 */
`;

/** 스냅샷에서 만든 타입 원문. 파일에 쓰는 것과 검사에 쓰는 것이 같아야 한다 */
export async function generate() {
  const schema = JSON.parse(await readFile(SCHEMA, 'utf8'));
  const ast = await openapiTS(schema, { alphabetize: true, emptyObjectsUnknown: true });

  return BANNER + astToString(ast);
}

async function main() {
  const source = await generate();
  const check = process.argv.includes('--check');

  if (!check) {
    await writeFile(OUTPUT, source, 'utf8');
    return;
  }

  const current = await readFile(OUTPUT, 'utf8').catch(() => null);

  if (current !== source) {
    process.exitCode = 1;
    console.error(
      current === null
        ? 'src/generated/api-schema.d.ts 가 없습니다. `npm run api:generate`를 실행하세요.'
        : 'src/generated/api-schema.d.ts 가 openapi.json 과 어긋납니다. `npm run api:generate`를 실행하세요.',
    );
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}
