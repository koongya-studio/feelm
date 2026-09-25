import js from '@eslint/js';
import prettierConfig from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  /*
   * `src/generated`는 백엔드 OpenAPI 스냅샷에서 만든 파일이다(업무 -777). 손으로 고치지
   * 않으므로 규칙을 적용해도 고칠 사람이 없고, 생성기 출력이 바뀔 때마다 규칙과 싸우게 된다.
   */
  { ignores: ['dist', 'node_modules', 'landing', 'docs', 'src/generated'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      ...tseslint.configs.recommended,
      reactHooks.configs.flat['recommended-latest'],
      reactRefresh.configs.vite,
      prettierConfig,
    ],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
  },
  {
    // 설정과 빌드 도구 스크립트. 브라우저가 아니라 Node에서 돈다
    files: ['vite.config.ts', 'eslint.config.js', 'scripts/**/*.mjs'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended, prettierConfig],
    languageOptions: {
      globals: globals.node,
    },
  },
  /*
   * 테스트 파일과 테스트 도우미 (업무 S15P21E106-333).
   *
   * `react-refresh/only-export-components`는 HMR 경계를 지키기 위한 규칙이다. 테스트
   * 파일은 개발 서버의 모듈 그래프에 들어가지 않으므로 이 규칙이 잡을 것이 없는데,
   * 도우미가 컴포넌트와 함수를 함께 내보내면 오류가 난다(`src/test/render.tsx`).
   */
  {
    files: ['src/**/*.{test,spec}.{ts,tsx}', 'src/test/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
);
