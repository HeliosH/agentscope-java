#!/usr/bin/env node
// Real browser and real backend; the parent seeds owned unknown-outcome facts, not mock APIs.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
  const base = process.env.BASE_URL;
  const agent = process.env.RECONCILIATION_AGENT_ID;
  const run = process.env.RECONCILIATION_RUN_ID;
  const operation = process.env.RECONCILIATION_OPERATION_ID;
  assert(['localhost', '127.0.0.1'].includes(new URL(base).hostname));
  assert(agent && run && operation && process.env.RECONCILIATION_PASSWORD);
  const output = process.env.SCREENSHOT_DIR || '/private/tmp/chugou-reconciliation-ui';
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || 'chrome' });
  const admin = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const member = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await admin.newPage();
  const owner = await member.newPage();
  const errors = [];
  [page, owner].forEach(p => { p.setDefaultTimeout(60000); p.on('pageerror', error => errors.push(error.message)); });
  const login = async (p, email) => {
    await p.goto(`${base}/login`);
    await p.getByLabel('Email', { exact: true }).fill(email);
    await p.getByLabel('Password', { exact: true }).fill(process.env.RECONCILIATION_PASSWORD);
    await p.getByRole('button', { name: 'Continue', exact: true }).click();
    await p.waitForURL(url => url.pathname !== '/login');
  };
  const review = () => page.getByRole('button', { name: `Review external_fixture ${operation}`, exact: true });
  const endpoint = `/api/admin/runs/${run}/operations/${operation}/reconcile`;
  try {
    await login(page, process.env.RECONCILIATION_ADMIN_EMAIL);
    await page.goto(`${base}/admin/operations`);
    await page.locator('.data-table tbody tr').first().waitFor();
    if (!await review().count()) {
      await page.getByRole('button', { name: 'Next page', exact: true }).click();
    }
    await review().click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Reason', { exact: true }).fill('Verified fixture receipt, outcome still unknown.');
    await dialog.getByLabel('External evidence reference', { exact: true }).fill('fixture://owned/receipt');
    let responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === endpoint);
    await dialog.getByRole('button', { name: 'Record evidence', exact: true }).click();
    assert.equal((await responsePromise).status(), 200);
    await dialog.waitFor({ state: 'hidden' });
    await review().click();
    await dialog.getByText('Evidence version: 1', { exact: true }).waitFor();
    await dialog.getByLabel('External outcome', { exact: true }).selectOption('CONFIRMED_SUCCESS');
    await dialog.getByLabel('Reason', { exact: true }).fill('External receipt confirms completion.');
    await dialog.getByLabel('External evidence reference', { exact: true }).fill('fixture://owned/final-receipt');
    await dialog.getByLabel('Confirmed external result', { exact: true }).fill('externally-confirmed-browser-result');
    // Commit at the actual backend, then discard only the response to exercise same-command retry.
    let firstCommand;
    await page.route(`**${endpoint}`, async route => {
      firstCommand = { key: route.request().headers()['idempotency-key'], body: route.request().postData() };
      const response = await route.fetch();
      assert.equal(response.status(), 200);
      await route.abort('failed');
      await page.unroute(`**${endpoint}`);
    }, { times: 1 });
    await dialog.getByRole('button', { name: 'Record evidence', exact: true }).click();
    await dialog.getByRole('button', { name: 'Retry submission', exact: true }).waitFor();
    assert(await dialog.getByLabel('Reason', { exact: true }).isDisabled());
    assert(await dialog.getByRole('button', { name: 'Close', exact: true }).last().isDisabled());
    await page.screenshot({ path: path.join(output, 'unconfirmed-desktop.png'), fullPage: true });
    responsePromise = page.waitForResponse(response => new URL(response.url()).pathname === endpoint);
    await dialog.getByRole('button', { name: 'Retry submission', exact: true }).click();
    const repeated = await responsePromise;
    assert.equal(repeated.status(), 200);
    assert.equal(repeated.request().headers()['idempotency-key'], firstCommand.key);
    assert.equal(repeated.request().postData(), firstCommand.body);
    assert.equal((await repeated.json()).reused, true);
    await dialog.waitFor({ state: 'hidden' });
    await review().click();
    await dialog.getByText('Effective: confirmed success', { exact: true }).waitFor();
    await dialog.getByText('Original: outcome unknown', { exact: true }).waitFor();
    await dialog.locator('.operation-evidence-entry').nth(1).waitFor();
    assert.equal(await dialog.locator('.operation-evidence-entry').count(), 2);
    assert.equal(await dialog.getByRole('button', { name: 'Record evidence', exact: true }).count(), 0);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: path.join(output, 'confirmed-desktop.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const bounds = await dialog.boundingBox();
    assert(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 390);
    await page.screenshot({ path: path.join(output, 'confirmed-mobile.png'), fullPage: true });
    await dialog.getByRole('button', { name: 'Close', exact: true }).last().click();
    await page.reload();
    await review().click();
    await dialog.getByText('Effective: confirmed success', { exact: true }).waitFor();

    await login(owner, process.env.RECONCILIATION_EMAIL);
    await owner.goto(`${base}/agents/${agent}/chat?session=${process.env.RECONCILIATION_SESSION_ID}`);
    await owner.getByRole('button', { name: 'Run details', exact: true }).click();
    const inspector = owner.getByRole('complementary', { name: 'Run details' });
    await inspector.getByRole('tab', { name: /Tools/ }).click();
    await inspector.getByText('confirmed success', { exact: true }).waitFor();
    assert.equal(await inspector.locator('.run-operation').count(), 25);
    assert.equal(await inspector.getByRole('button', { name: /Record|Replay|Retry/ }).count(), 0);
    await owner.screenshot({ path: path.join(output, 'employee-desktop.png'), fullPage: true });
    await inspector.getByRole('button', { name: 'Next', exact: true }).click();
    await inspector.locator('.run-operation').nth(2).waitFor();
    assert.equal(await inspector.locator('.run-operation').count(), 3);
    await inspector.getByRole('button', { name: 'First', exact: true }).click();
    await inspector.getByText('confirmed success', { exact: true }).waitFor();
    await owner.setViewportSize({ width: 390, height: 844 });
    await owner.waitForFunction(() => {
      const sidebar = document.querySelector('.agent-sidebar');
      return sidebar && sidebar.getBoundingClientRect().right <= 1;
    });
    assert(await owner.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const inspectorBounds = await inspector.boundingBox();
    assert(inspectorBounds && inspectorBounds.x >= 0 && inspectorBounds.x + inspectorBounds.width <= 391);
    assert(await inspector.locator('.run-operation').first().evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
    }), 'Closed navigation must not cover the employee tool record');
    await owner.screenshot({ path: path.join(output, 'employee-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    console.log('PASS real admin/employee browser evidence, same-key ambiguous-response retry, refresh, cursor pages and desktop/mobile');
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'failure-admin.png'), fullPage: true });
    await owner.screenshot({ path: path.join(output, 'failure-employee.png'), fullPage: true });
    console.error('Browser reconciliation failure:', errors, await page.locator('body').innerText(), await owner.locator('body').innerText());
    throw error;
  } finally { await admin.close(); await member.close(); await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
