import { defineConfig } from 'vitest/config';
export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['tests/live-dom.test.tsx'],
    testTimeout: 180000,
    hookTimeout: 10000,
    fileParallelism: false,
    maxWorkers: 1,
  },
});
