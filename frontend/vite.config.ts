import react from '@vitejs/plugin-react';
// `test` 블록의 타입은 vitest가 준다. `vite`에서 가져오면 타입 검사에서 걸린다
import { defineConfig } from 'vitest/config';
// `loadEnv`는 vitest가 다시 export하지 않으므로 vite에서 직접 가져온다
import { loadEnv } from 'vite';
import { generate } from './scripts/api-types.mjs';
import { readFile } from 'node:fs/promises';
import type { Plugin } from 'vite';

/** 개발에서 `/api`를 넘길 백엔드. `.env`의 `VITE_API_PROXY_TARGET`으로 바꾼다 */
const DEFAULT_API_TARGET = 'http://localhost:8080';

/**
 * 생성 타입이 OpenAPI 스냅샷과 어긋나면 빌드를 세운다 (업무 S15P21E106-777).
 *
 * **이 자리인 이유.** 프론트 MR Job이 돌리는 명령은 CI 서버에 설치된 러너에 고정되어
 * 있고(`infra/scripts/ci-frontend-mr-runner.py`의 `FIXED_COMMAND`), 그 계약이 `npm run`을
 * 금지한다(`infra/scripts/test_ci_frontend_mr.py`). 고정 명령 안에서 검사를 걸 수 있는
 * 곳은 `vite build`뿐이다. 러너 계약을 바꾸는 쪽은 인프라와 합의할 후속 과제다.
 *
 * `apply: 'build'`라 개발 서버와 vitest는 지나지 않는다 — 스냅샷을 갱신하는 도중에
 * 개발 서버가 멈추면 안 되고, 테스트는 이 검사의 대상이 아니다.
 */
function apiSchemaDriftCheck(): Plugin {
  return {
    name: 'feelm-api-schema-drift',
    apply: 'build',
    async buildStart() {
      const expected = await generate();
      const current = await readFile(
        new URL('./src/generated/api-schema.d.ts', import.meta.url),
        'utf8',
      ).catch(() => null);

      if (current !== expected) {
        this.error(
          'src/generated/api-schema.d.ts 가 openapi.json 과 어긋납니다. `npm run api:generate`를 실행하세요.',
        );
      }
    },
  };
}

/** 개발 서버가 `/api` · `/media`를 넘길 백엔드. `.env`의 `VITE_API_PROXY_TARGET`이 정한다 */
function apiTarget(mode: string): string {
  return loadEnv(mode, process.cwd(), '').VITE_API_PROXY_TARGET || DEFAULT_API_TARGET;
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  base: '/app/',
  plugins: [react(), apiSchemaDriftCheck()],
  server: {
    port: 5173,
    /*
     * 백엔드를 같은 출처로 만든다(업무 S15P21E106-364).
     *
     * 프론트가 `/api/v1/...`을 상대 경로로 부르므로 개발에서도 배포와 같은 모양이 된다
     * (배포는 Nginx가 같은 경로를 넘긴다 · infra/nginx/nginx.conf). 브라우저가 교차
     * 출처로 보지 않으니 CORS 설정에 기대지 않아도 되고, Refresh Token 쿠키도 그대로
     * 실린다. 쿠키에 `Secure`가 붙어 있지만 브라우저가 localhost를 보안 컨텍스트로 보아
     * http에서도 저장된다.
     */
    proxy: {
      // 배포 Nginx와 같은 TMDB 전용 경로. 인증정보는 외부로 전달하지 않는다.
      '^/tmdb-images/(?:original|w[1-9][0-9]*)/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)$': {
        target: 'https://image.tmdb.org',
        changeOrigin: true,
        rewrite: (path) => path.replace('/tmdb-images/', '/t/p/'),
        configure: (proxy) => {
          proxy.on('proxyReq', (request) => {
            for (const header of request.getHeaderNames()) request.removeHeader(header);
            request.setHeader('host', 'image.tmdb.org');
          });
          proxy.on('proxyRes', (response) => {
            delete response.headers['set-cookie'];
          });
        },
      },
      '/api': { target: apiTarget(mode), changeOrigin: true },
      // 프로필 사진(API-014 · 062 `avatarUrl`)은 `/media/avatars/…` 상대 주소로 온다(업무 -762)
      '/media': { target: apiTarget(mode), changeOrigin: true },
    },
  },
  // landing/은 독립 배포하는 정적 페이지이므로 Vite의 의존성 스캔 대상에서 제외한다.
  // 제외하지 않으면 landing/index.html이 import하는 CDN 의존성을 node_modules에서
  // 찾으려다 실패한다. (frontend/README.md 참고)
  optimizeDeps: {
    entries: ['index.html'],
  },

  /*
   * 테스트 환경 (업무 S15P21E106-333).
   *
   * Vite 설정을 그대로 물려받으므로 CSS Modules · 경로 해석 · `import.meta.env`가
   * 화면과 같게 동작한다. 별도 트랜스폼 설정이 필요한 Jest 대신 Vitest를 쓴 이유다.
   *
   * Vitest 4와 Vite 8을 사용한다. CI와 이미지 빌드는 Node 22.22.0에서 실행한다.
   */
  test: {
    // 화면 컴포넌트를 렌더하므로 브라우저 DOM이 필요하다
    environment: 'jsdom',
    /*
     * `globals`를 켜지 않는다. `describe`·`it`·`expect`를 파일마다 명시적으로
     * import하므로 켜 두면 죽은 설정이 되고, 전역 타입 선언도 따로 필요해진다.
     */
    setupFiles: ['./src/test/setup.ts'],
    /*
     * 시험 하나의 예산 (업무 S15P21E106-797).
     *
     * 기본값 5초는 장면이 도는 화면(5.9 팝콘 섞기 · EXT 분석)이 바쁠 때 그대로 넘긴다 —
     * CPU를 점유한 전체 실행에서 한 시험이 7.5초까지 갔고, 5초에 걸려 깨진 것이 간헐
     * 실패의 절반이었다. 기다리는 단정문의 예산(`src/test/setup.ts`)과 같은 이유로 늘린다.
     */
    testTimeout: 20_000,
    // 소스 옆이 아니라 파일명 규칙으로 찾는다. 위치는 대상 파일 옆에 둔다
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: true,
  },
}));
