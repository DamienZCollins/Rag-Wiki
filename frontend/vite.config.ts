import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_BASE_URL || 'http://localhost:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      strictPort: false,
      // 截图脚本与产物不参与热更新监听，避免写入临时文件时触发 EBUSY
      // 原子替换（编辑器写临时目录再改名）也会落在项目根，一并忽略
      watch: {
        ignored: [
          '**/scripts/**',
          '**/.artifacts/**',
          '**/tests/**',
          '**/test-results/**',
          '**/*.tmpdir/**',
          '**/.*.tmpdir/**',
          '**/*.tmp',
        ],
      },
      proxy: {
        // 开发期走同源代理，避免浏览器跨域与 SSE 预检问题
        '/api': {
          target,
          changeOrigin: true,
        },
        '/actuator': {
          target,
          changeOrigin: true,
        },
      },
    },
  }
})
