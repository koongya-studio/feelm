import js from '@eslint/js';
import prettierConfig from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'landing', 'docs'] },
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
    files: ['vite.config.ts', 'eslint.config.js'],
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
