import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev: gọi API qua proxy tới app Spring (mvnw spring-boot:run, cổng 8080) nên không cần CORS.
// Prod: Caddy phục vụ file build và proxy /api, cũng cùng origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: { environment: 'node' },
})
