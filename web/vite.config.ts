import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 网关是唯一入口（TECHNICAL §2.3 / §6.1）：
 * /api/** 与 /openapi/** 一律代理到 8080，前端不直连 8082/8083。
 * dev 与 preview 必须同配，否则 `vite preview` 的联调会出现“本地能过、预览 404”。
 */
const GATEWAY = process.env.VITE_GATEWAY ?? 'http://127.0.0.1:8080'

const proxy = {
  '/api': { target: GATEWAY, changeOrigin: true },
  '/openapi': { target: GATEWAY, changeOrigin: true },
}

export default defineConfig({
  base: '/',
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // Element Plus 全量引入（含中文语言包）单块约 920 KB / gzip 约 300 KB：
    // 内网管理后台可接受，先保证行为一致；按需引入留待页面齐备后统一收口。
    chunkSizeWarningLimit: 1024,
    rollupOptions: {
      output: {
        // UI 库与框架各自成块：业务页懒加载时不会把依赖重复打进每个 chunk
        manualChunks: {
          vendor: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus'],
        },
      },
    },
  },
})
