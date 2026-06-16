import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Build output goes into the Spring Boot static resources so the backend serves the SPA
// same-origin (no CORS, SSE works directly). Dev server proxies /api to the backend.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
