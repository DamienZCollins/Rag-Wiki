import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import tseslint from '@typescript-eslint/eslint-plugin'
import tsparser from '@typescript-eslint/parser'
import vueParser from 'vue-eslint-parser'

/** 脚本、配置与测试跑在 Node 上；页面代码跑在浏览器里 */
const nodeGlobals = {
  process: 'readonly',
  console: 'readonly',
  Buffer: 'readonly',
  __dirname: 'readonly',
  __filename: 'readonly',
  URL: 'readonly',
  setTimeout: 'readonly',
  clearTimeout: 'readonly',
}

const browserGlobals = {
  console: 'readonly',
  document: 'readonly',
  window: 'readonly',
  localStorage: 'readonly',
  fetch: 'readonly',
  AbortController: 'readonly',
  ReadableStream: 'readonly',
  TextDecoder: 'readonly',
  Response: 'readonly',
  Request: 'readonly',
  Headers: 'readonly',
  setTimeout: 'readonly',
  clearTimeout: 'readonly',
  setInterval: 'readonly',
  clearInterval: 'readonly',
  HTMLElement: 'readonly',
  HTMLButtonElement: 'readonly',
  KeyboardEvent: 'readonly',
  Event: 'readonly',
  CustomEvent: 'readonly',
  ResizeObserver: 'readonly',
  IntersectionObserver: 'readonly',
  MutationObserver: 'readonly',
  getComputedStyle: 'readonly',
  requestAnimationFrame: 'readonly',
  cancelAnimationFrame: 'readonly',
  queueMicrotask: 'readonly',
}

export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'playwright-report/**',
      'test-results/**',
      '.artifacts/**',
      'src/styles/**',
    ],
  },
  js.configs.recommended,
  ...vue.configs['flat/recommended'],
  {
    files: ['**/*.ts', '**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tsparser,
        ecmaVersion: 'latest',
        sourceType: 'module',
        extraFileExtensions: ['.vue'],
      },
      globals: browserGlobals,
    },
    plugins: {
      '@typescript-eslint': tseslint,
    },
    rules: {
      'no-undef': 'off',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      'vue/multi-word-component-names': 'off',
      // 可选 props 用 TS 可选标记表达，不再要求提供默认值
      'vue/require-default-prop': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-indent': ['error', 2],
      'vue/attributes-order': 'off',
    },
  },
  {
    // 构建配置、截图脚本、e2e 测试都跑在 Node 上；
    // 脚本里 page.evaluate 的回调实际在浏览器上下文执行，所以两套 globals 都要给
    files: ['scripts/**/*.mjs', 'scripts/**/*.js', '*.config.js', 'tests/**/*.ts'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...nodeGlobals,
        ...browserGlobals,
        location: 'readonly',
        navigator: 'readonly',
        performance: 'readonly',
      },
    },
  },
]
