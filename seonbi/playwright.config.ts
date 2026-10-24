import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';

const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:5173', viewport: { width: 1280, height: 920 }, headless: true, launchOptions: existsSync(macChrome) ? { executablePath: macChrome } : undefined, screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev', url: 'http://127.0.0.1:5173', reuseExistingServer: !process.env.CI },
});
