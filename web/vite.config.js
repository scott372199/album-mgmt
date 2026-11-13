import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发态把 /api 代理到本地 Spring Boot；生产由同一 origin 提供前端与 API。
// Dev proxies /api to the local Spring Boot; in production one origin serves both.
export default defineConfig({
  plugins: [react()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    proxy: {
      '/api': {
        target: process.env.MGMT_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
});
