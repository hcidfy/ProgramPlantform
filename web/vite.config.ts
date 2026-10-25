import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiProxy = {
  '/api': {
    target: 'http://127.0.0.1:8080',
    changeOrigin: true
  }
}

// 开发期把 /api 代理到本机 app（8080），生产由 Nginx 反代（deploy/nginx/default.conf）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: apiProxy
  },
  preview: {
    port: 4173,
    proxy: apiProxy
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
})
