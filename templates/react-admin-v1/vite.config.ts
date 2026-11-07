/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: { port: 3000, host: true },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // react-router ships CommonJS and ESM builds; process it with React Admin so tests load one copy.
    server: { deps: { inline: [/react-admin/, /ra-/, /react-router/] } },
  },
});
