import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

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
});
