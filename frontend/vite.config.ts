/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: UI chạy ở :5173, proxy /api sang backend để UI và API cùng origin (giống prod sau reverse proxy, không cần CORS).
// Short link (GET /{code}) do backend phục vụ ở :8080 (APP_BASE_URL), không đi qua dev server.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_API_TARGET ?? 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
