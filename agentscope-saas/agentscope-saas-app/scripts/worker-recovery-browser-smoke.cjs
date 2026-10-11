#!/usr/bin/env node
// Actual browser -> web/API -> PG -> separate worker. The parent owns a local model HTTP fixture.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

const base = process.env.BASE_URL;
const agent = process.env.RECOVERY_AGENT_ID;
const marker = process.env.RECOVERY_MARKER;
const output = process.env.SCREENSHOT_DIR || '/private/tmp/chugou-real-recovery-ui';

(async () => {
  assert(['localhost', '127.0.0.1'].includes(new URL(base).hostname));
  assert(agent && marker && process.env.RECOVERY_EMAIL && process.env.RECOVERY_PASSWORD);
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || 'chrome' });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.setDefaultTimeout(120000);
  const errors = [];
  let executions = 0;
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (new URL(request.url()).pathname.endsWith('/chat/stream')) executions++; });
  try {
    await page.goto(`${base}/login`);
    await page.getByLabel('Email', { exact: true }).fill(process.env.RECOVERY_EMAIL);
    await page.getByLabel('Password', { exact: true }).fill(process.env.RECOVERY_PASSWORD);
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await page.waitForURL(url => url.pathname !== '/login');
    await page.goto(`${base}/agents/${agent}/chat?new=recovery`);
    const composer = page.getByPlaceholder('Message ConsumerSmoke');
    await composer.fill(marker);
    const responsePromise = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/chat/stream'));
    await page.getByRole('button', { name: 'Send message', exact: true }).click();
    const response = await responsePromise;
    assert.equal(response.status(), 200);
    await page.getByRole('status').filter({ hasText: 'Waiting for execution resources' }).waitFor();
    // SPA history navigation can evict Chrome's completed streaming response body. Re-read the
    // owned authoritative state, rather than relying on a browser protocol response cache.
    const authorization = response.request().headers().authorization;
    const pointerResponse = await context.request.get(`${base}/api/agents/${agent}/chat/session`,
      { headers: { Authorization: authorization } });
    assert.equal(pointerResponse.status(), 200);
    const pointer = await pointerResponse.json();
    const sessionId = new URL(page.url()).searchParams.get('session');
    assert.equal(pointer.sessionKey, sessionId);
    const stateResponse = await context.request.get(`${base}/api/agents/${agent}/runs/${pointer.latestRunId}/recovery`,
      { headers: { Authorization: authorization } });
    assert.equal(stateResponse.status(), 200);
    assert.equal((await stateResponse.json()).state, 'WAITING_FOR_WORKER');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: path.join(output, 'waiting-desktop.png'), fullPage: true });
    await page.getByRole('button', { name: 'Close run details', exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.reload();
    await page.getByRole('status', { name: '正在恢复任务', exact: true }).waitFor();
    await composer.fill('Preserved next-task draft');
    assert(await page.getByRole('button', { name: 'Send message', exact: true }).isDisabled());
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: path.join(output, 'waiting-mobile.png'), fullPage: true });
    console.log(JSON.stringify({ ready: true, runId: pointer.latestRunId, sessionId }));
    await page.locator('.message-row--assistant .message-content').filter({ hasText: 'Recovered ' + marker }).waitFor();
    assert.equal(await composer.inputValue(), 'Preserved next-task draft');
    assert(await page.getByRole('button', { name: 'Send message', exact: true }).isEnabled());
    assert.equal(executions, 1, 'Reload and recovery must not submit the task again');
    assert.deepEqual(errors, []);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: path.join(output, 'completed-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.screenshot({ path: path.join(output, 'completed-desktop.png'), fullPage: true });
    console.log('PASS real browser login, task submission, worker waiting/restart, automatic final reply, desktop/mobile and no replay');
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true });
    console.error('Real browser recovery failure:', page.url(), errors, await page.locator('body').innerText());
    throw error;
  } finally { await context.close(); await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
