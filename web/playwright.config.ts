import { defineConfig } from "@playwright/test";

/**
 * Smoke tests against servers that are already running: the web app on :3000 and core-service
 * on :8080 (see README.md). They use your installed Chrome, so no browser download is needed.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.WEB_URL ?? "http://localhost:3000",
    channel: "chrome",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
});
