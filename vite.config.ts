import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Relative base so the production build can be hosted from any sub-path
// (or wrapped by a future Tauri shell) without changes.
export default defineConfig({
  plugins: [react()],
  base: './',
  server: { port: 5173 },
  build: { chunkSizeWarningLimit: 1500 },
});
