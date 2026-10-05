import { test, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
test('public browsing, filtering, verification and responsive layout', async ({ page }, info) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Drinking water', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Medical', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'ORS packets' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Drinking water', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'All needs' }).click();
  await mkdir('.impeccable/review', { recursive: true });
  await page.screenshot({
    path: `.impeccable/review/${info.project.name}.png`,
    fullPage: true,
    scale: 'css',
  });
  await page.screenshot({
    path: `.impeccable/review/${info.project.name}-viewport.png`,
    scale: 'css',
  });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.getByRole('link', { name: 'Verify', exact: true }).click();
  await page.getByLabel('Request ID or Saathi link').fill('SAA-2A6D19');
  await page.getByRole('button', { name: 'Check request' }).click();
  await expect(page.getByText('This need has been fulfilled', { exact: true })).toBeVisible();
  await expect(page.getByText('Do not send additional supplies.', { exact: false })).toBeVisible();
});
test('a volunteer creates a need, a guest contributes, and delivery closes the canonical request', async ({
  page,
  browser,
}) => {
  const title = `E2E relief kits ${Date.now()}`;
  await page.goto('/login');
  await page.getByLabel('Email', { exact: true }).fill('volunteer@saathi.test');
  await page
    .getByLabel('Password', { exact: true })
    .fill(process.env.SEED_PASSWORD ?? 'Saathi-demo-2026!');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page).toHaveURL('/dashboard');
  await page.getByRole('link', { name: 'Create a need', exact: true }).click();
  await page.getByLabel('What is needed?').fill(title);
  await page
    .getByLabel('Details', { exact: true })
    .fill('A verified request created by an automated browser test.');
  await page.getByLabel('Quantity', { exact: true }).fill('12');
  await page.getByLabel('Unit', { exact: true }).fill('kits');
  await page.getByLabel('Needed before (your local time)').fill('2099-01-01T18:00');
  await page.getByRole('button', { name: 'Publish verified request' }).click();
  await expect(page).toHaveURL(/\/r\/SAA-/);
  const publicId = page.url().split('/').pop()!;
  const donorContext = await browser.newContext({
    baseURL: 'http://localhost:3000',
    timezoneId: 'Asia/Kolkata',
  });
  const donor = await donorContext.newPage();
  await donor.goto(`/r/${publicId}`);
  await donor.getByLabel('Quantity', { exact: true }).fill('12');
  await donor.getByRole('button', { name: 'Reserve 12 kits' }).click();
  await expect(donor).toHaveURL(/\/contribution\//);
  await donor.getByLabel('Delivery reference (e.g. your name)').fill('TEST-DELIVERY');
  await donor.getByLabel('Expected arrival (your local time)').fill('2099-01-01T16:00');
  await donor.getByRole('button', { name: 'Record delivery details' }).click();
  await expect(donor.getByRole('heading', { name: 'Help is on its way' })).toBeVisible();
  await page.goto('/dashboard');
  let incoming = page.locator('.incoming-row').filter({ hasText: title });
  await incoming.getByRole('button', { name: 'Order seen' }).click();
  incoming = page.locator('.incoming-row').filter({ hasText: title });
  await incoming.getByRole('button', { name: 'Mark received' }).click();
  await expect(incoming).toHaveCount(0);
  await expect(donor.getByRole('heading', { name: 'Your help arrived.' })).toBeVisible({
    timeout: 20000,
  });
  await donor.goto(`/r/${publicId}`);
  await expect(donor.getByText('This need has been fulfilled', { exact: true })).toBeVisible();
  await expect(donor.getByRole('button', { name: /Reserve/ })).toHaveCount(0);
  await donor.goto('/');
  await expect(donor.getByRole('heading', { name: title, exact: true })).toHaveCount(0);
  await donorContext.close();
});
test('theme preference persists and page remains readable without horizontal overflow', async ({
  page,
}, info) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Use dark theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.getByRole('heading', { name: 'Drinking water', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({
    path: `.impeccable/review/${info.project.name}-dark.png`,
    fullPage: true,
  });
});
