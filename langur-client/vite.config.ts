import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 接入层外置（P3）：Client 与 Server 仅 HTTP 契约耦合，无编译期依赖。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 开发态把 /api 转发到后端（§4.3），生产态为同源单 jar，无需代理
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});