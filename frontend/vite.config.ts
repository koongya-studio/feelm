import react from '@vitejs/plugin-react';
// `test` 블록의 타입은 vitest가 준다. `vite`에서 가져오면 타입 검사에서 걸린다
import { defineConfig } from 'vitest/config';

// https://vite.dev/config/
export default defineConfig({
  base: '/app/',
  plugins: [react()],
  server: {
    port: 5173,
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
   * **Vitest 4를 쓴다.** 최신 5는 Node `^22.12` 이상만 지원하는데 CI의 `frontend-test`
   * 잡이 `node:20.19-alpine`에서 돈다(`.gitlab-ci.yml`). 4는 `^20 || ^22 || >=24`이고
   * Vite 8도 peer로 받는다. 둘을 동시에 만족하는 유일한 메이저다.
   */
  test: {
    // 화면 컴포넌트를 렌더하므로 브라우저 DOM이 필요하다
    environment: 'jsdom',
    /*
     * `globals`를 켜지 않는다. `describe`·`it`·`expect`를 파일마다 명시적으로
     * import하므로 켜 두면 죽은 설정이 되고, 전역 타입 선언도 따로 필요해진다.
     */
    setupFiles: ['./src/test/setup.ts'],
    // 소스 옆이 아니라 파일명 규칙으로 찾는다. 위치는 대상 파일 옆에 둔다
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: true,
  },
});
