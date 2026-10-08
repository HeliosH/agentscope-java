#!/usr/bin/env node
// Browser interaction contract against explicit API fixtures; not a real-backend E2E gate.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:5188';
const output = process.env.SCREENSHOT_DIR || '/private/tmp/chugou-model-policy-ui';

async function run(browser, viewport) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const policies = ['COMPACTION', 'MEMORY_EXTRACT', 'MEMORY_CONSOLIDATE', 'VERIFY'].map(purpose => ({
    orgId: 'organization-fixture', purpose, modelId: null,
    maxInputTokens: 8000, maxOutputTokens: 1024, timeoutSeconds: 30, version: 'defaults-v1',
  }));
  const models = [{ id: 'long', displayName: 'Enterprise long-context model', modelName: 'private-model',
    source: 'managed', providerType: 'gateway', enabled: true, defaultModel: true,
    contextWindowTokens: 1048576, maxOutputTokens: 4096, safetyMarginTokens: 1024, version: 1 }];
  let conflict = false;
  let savedBody;
  let memoryFilter;
  const memoryEvents = ['failed', 'dead_letter'].map((syncStatus, index) => ({
    id: `memory-${index}`, userId: 'user-fixture', orgId: 'organization-fixture',
    agentId: 'assistant', sessionId: 'session-fixture', source: 'mem0', eventType: 'conversation',
    syncStatus, syncAttempts: index === 0 ? 1 : 10, lastError: 'MEMORY_PROJECTION_IO_ERROR',
    createdAt: '2026-10-06T04:00:00Z', contentJson: '{}', metadataJson: '{}',
  }));
  await context.route('**/api/**', async route => {
    const request = route.request();
    const endpoint = new URL(request.url()).pathname;
    if (!endpoint.startsWith('/api/')) return route.continue();
    let body = [], status = 200;
    if (endpoint === '/api/auth/me') body = { userId: 'user-fixture', orgId: 'organization-fixture', email: 'admin@example.test', role: 'admin', tier: 'enterprise' };
    else if (endpoint === '/api/admin/models') body = models;
    else if (endpoint === '/api/admin/memory-events') {
      memoryFilter = new URL(request.url()).searchParams.get('syncStatus');
      body = memoryEvents.filter(item => !memoryFilter || item.syncStatus === memoryFilter);
    }
    else if (endpoint === '/api/admin/model-invocations/policies') body = policies;
    else if (endpoint.startsWith('/api/admin/model-invocations/policies/') && request.method() === 'PUT') {
      savedBody = request.postDataJSON();
      const purpose = endpoint.split('/').pop();
      const policy = policies.find(item => item.purpose === purpose);
      if (conflict || savedBody.version !== policy.version) { status = 409; body = { error: 'Version conflict' }; }
      else { Object.assign(policy, savedBody, { version: 'saved-v2' }); body = policy; }
    } else { errors.push(`Unexpected API request: ${request.method()} ${endpoint}`); status = 404; }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  });
  try {
    await page.goto(`${baseUrl}/admin/models`);
    await page.getByRole('button', { name: 'Edit context compression policy' }).waitFor();
    await page.screenshot({ path: path.join(output, `catalog-${viewport.width}.png`), fullPage: true });
    await page.getByRole('button', { name: 'Edit context compression policy' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    await dialog.getByLabel('Model', { exact: true }).selectOption('long');
    await dialog.getByLabel('Maximum input tokens').fill('12000');
    await dialog.getByLabel('Maximum output tokens').fill('2048');
    await dialog.getByLabel('Timeout (seconds)').fill('45');
    const geometry = await dialog.boundingBox();
    assert(geometry.x >= 0 && geometry.x + geometry.width <= viewport.width + 1, 'Dialog must fit viewport');
    await page.screenshot({ path: path.join(output, `editor-${viewport.width}.png`), fullPage: true });
    await dialog.getByRole('button', { name: 'Save policy', exact: true }).click();
    await page.getByText('Model invocation policy saved.', { exact: true }).waitFor();
    assert.equal(savedBody.orgId, undefined);
    assert.equal(savedBody.purpose, undefined);
    assert.equal(savedBody.maxInputTokens, 12000);
    assert.equal(savedBody.version, 'defaults-v1');
    await page.reload();
    await page.getByRole('button', { name: 'Edit context compression policy' }).click();
    assert.equal(await dialog.getByLabel('Maximum input tokens').inputValue(), '12000');
    assert.equal(await dialog.getByLabel('Model', { exact: true }).inputValue(), 'long');
    conflict = true;
    await dialog.getByRole('button', { name: 'Save policy', exact: true }).click();
    await dialog.getByText('Policy changed by another administrator. Refresh before saving again.').waitFor();
    assert.equal(savedBody.version, 'saved-v2');
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.goto(`${baseUrl}/admin/memory-events`);
    await page.locator('tbody tr').first().waitFor();
    assert.equal(await page.locator('tbody tr').count(), 2);
    await page.getByLabel('Projection status').selectOption('dead_letter');
    await page.getByRole('button', { name: 'Apply', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('tbody tr').length === 1);
    assert.equal(memoryFilter, 'dead_letter');
    assert.match(await page.locator('tbody').innerText(), /dead.letter/i);
    await page.locator('tbody tr').scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, `memory-${viewport.width}.png`), fullPage: true });
    assert.deepEqual(errors, []);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'No page overflow');
    console.log(`PASS ${viewport.width}x${viewport.height}: policies, conflict, memory dead-letter filter, layout`);
  } finally { await context.close(); }
}

(async () => {
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || undefined });
  try {
    await run(browser, { width: 1440, height: 1000 });
    await run(browser, { width: 390, height: 844 });
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
