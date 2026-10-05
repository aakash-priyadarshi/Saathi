import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: 'tests/e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 60000,
  expect: { timeout: 15000 },
  use: {
    baseURL: 'http://localhost:3000',
    timezoneId: 'Asia/Kolkata',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } },
    },
    { name: 'mobile', use: { ...devices['Pixel 7'], viewport: { width: 390, height: 844 } } },
  ],
  webServer: process.env.CI
    ? [
        {
          command: 'pnpm --filter @saathi/api start',
          url: 'http://localhost:4000/ready',
          reuseExistingServer: false,
          timeout: 60000,
        },
        {
          command: 'pnpm --filter @saathi/web start',
          url: 'http://localhost:3000',
          reuseExistingServer: false,
          timeout: 60000,
        },
      ]
    : undefined,
});
