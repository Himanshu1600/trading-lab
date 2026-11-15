import { expect, test, type Page } from "@playwright/test";

/**
 * One new user walks through the app: sign up, see a live price, place and cancel an order,
 * save a strategy and run a backtest. Any uncaught error in the page fails the test.
 *
 * Each run creates a user named smoke-<time>@example.com. Remove them with:
 *   DELETE FROM users WHERE email LIKE 'smoke-%@example.com';
 */
test("a new user can trade, save a strategy and run a backtest", async ({ page }) => {
  const pageErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));

  await signUp(page);

  await test.step("the Trade page shows a price and a chart", async () => {
    await page.goto("/trade?symbol=AAPL");
    await expect(page.getByText("Apple", { exact: false }).first()).toBeVisible();
    await expect(page.getByText("Bid")).toBeVisible();
    await expect(page.getByLabel("AAPL price chart").locator("canvas").first()).toBeVisible();
  });

  await test.step("a limit order far below the market stays open", async () => {
    await page.getByLabel("Order type").selectOption("LIMIT");
    await page.getByLabel("Limit price").fill("1");
    await page.getByLabel("Time in force").selectOption("GTC");
    await page.getByRole("button", { name: "Buy AAPL" }).click();
    await expect(page.getByText("Buy order placed for 1 AAPL.")).toBeVisible();
  });

  await test.step("the order shows on Portfolio and can be cancelled", async () => {
    await page.getByRole("link", { name: "View orders" }).click();
    await expect(page.getByRole("heading", { name: "Open orders" })).toBeVisible();
    await page.getByRole("button", { name: "Cancel BUY AAPL" }).click();
    await expect(page.getByRole("heading", { name: "Order history" })).toBeVisible();
    await expect(page.getByText("cancelled", { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Open orders" })).toBeHidden();
  });

  await test.step("a template strategy saves and backtests", async () => {
    await page.getByRole("link", { name: "Strategies" }).click();
    await page.getByRole("button", { name: "Check" }).click();
    await expect(page.getByText("This strategy is valid.")).toBeVisible();
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByText('Saved "20/50 SMA crossover".')).toBeVisible();
    await page.getByRole("button", { name: "Run backtest" }).click();

    await page.waitForURL(/\/backtests\?run=/);
    // The first run for a stock downloads five years of prices, so allow it time.
    await expect(page.getByText("completed", { exact: true })).toBeVisible({ timeout: 90_000 });
    await expect(page.getByRole("rowheader", { name: "Total return" })).toBeVisible();
    await expect(page.getByLabel("Equity and drawdown chart").locator("canvas").first()).toBeVisible();
  });

  await test.step("the run is listed", async () => {
    await page.getByRole("link", { name: "← All backtests" }).click();
    await expect(page.getByRole("link", { name: "20/50 SMA crossover" })).toBeVisible();
  });

  await test.step("every AI feature says whether it's switched on", async () => {
    const badge = /AI ready|AI · not configured/;
    const shots = process.env.SCREENSHOT_DIR;
    for (const path of ["/strategies", "/journal", "/research"]) {
      await page.goto(path);
      await expect(page.getByText(badge).first()).toBeVisible();
      if (shots) await page.screenshot({ path: `${shots}${path}.png`, fullPage: true });
    }
  });

  expect(pageErrors).toEqual([]);
});

async function signUp(page: Page) {
  await page.goto("/register");
  await page.getByPlaceholder("Your name").fill("Smoke Test");
  await page.getByPlaceholder("you@example.com").fill(`smoke-${Date.now()}@example.com`);
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery");
  await page.getByRole("button", { name: "Sign up" }).click();
  await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
}
