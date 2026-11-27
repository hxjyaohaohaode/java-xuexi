import { defineConfig } from 'vitest/config';

export default defineConfig({
  server: { proxy: { '/api': 'http://127.0.0.1:8080' } },
  build: { target: 'es2022', sourcemap: false, license: { fileName: 'assets/third-party-licenses.txt' } },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },
});
