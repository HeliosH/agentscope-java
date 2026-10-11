#!/usr/bin/env node
// Browser interaction contract against API fixtures, not a real-backend E2E gate.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:5190';
const output = process.env.SCREENSHOT_DIR || '/private/tmp/chugou-workspace-archive-ui';
const recoveryMode = process.env.RECOVERY_SMOKE === 'true';

async function run(browser, viewport) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const agent = '11111111-1111-1111-1111-111111111111';
  const session = '22222222-2222-2222-2222-222222222222';
  const runId = '33333333-3333-3333-3333-333333333333';
  const version = '44444444-4444-4444-4444-444444444444';
  const agentBase = `/api/agents/${agent}`;
  let phase = 'fresh';
  let executions = 0;
  const agentView = { id: agent, name: 'Archive Fixture', status: 'active', enabled: true };
  await context.route('**/api/**', async route => {
    const endpoint = new URL(route.request().url()).pathname;
    if (!endpoint.startsWith('/api/')) return route.continue();
    let status = 200;
    let body = [];
    if (endpoint === '/api/auth/me') body = { userId: 'employee-fixture', orgId: 'org-fixture', email: 'employee@example.test', role: 'member', tier: 'standard' };
    else if (endpoint === '/api/agents') body = [agentView];
    else if (endpoint === agentBase) body = agentView;
    else if (endpoint === '/api/models') body = { defaultModelId: 'internal', models: [{ id: 'internal', displayName: 'Internal model', modelName: 'private', contextWindowTokens: 32768, maxOutputTokens: 4096, defaultModel: true }] };
    else if (endpoint === `${agentBase}/sessions/inbox`) body = phase === 'fresh' ? [] : [{ sessionKey: session, sessionId: session, agentId: agent, label: 'Archive task', unread: false, lastActivityMs: Date.now() }];
    else if (endpoint === `${agentBase}/chat/session`) body = { sessionKey: phase === 'fresh' ? null : session, exists: phase !== 'fresh', latestRunId: phase === 'fresh' ? null : runId };
    else if (endpoint === `${agentBase}/workspace/files`) body = [];
    else if (endpoint === `${agentBase}/chat/stream`) {
      executions++;
      phase = 'archiving';
      const events = [
        { type: 'RUN_STARTED', runId, threadId: session },
        { type: 'TEXT_MESSAGE_CONTENT', delta: 'Compute finished.' },
        recoveryMode
          ? { type: 'CUSTOM', name: 'run_recovery_scheduled', runId, threadId: session, value: {
              runId, sessionId: session, scheduled: true, runStatus: 'RUNNING', state: 'WAITING_FOR_WORKER',
              message: '任务已进入恢复队列，正在等待执行资源', attempt: 2, maxAttempts: 3,
            } }
          : { type: 'CUSTOM', name: 'run_archiving', runId, threadId: session, value: { runId, sessionId: session, archiveJobId: 'archive-fixture' } },
      ];
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: events.map(event => `data: ${JSON.stringify(event)}\n\n`).join('') });
    } else if (endpoint === `${agentBase}/runs/${runId}`) body = { id: runId, sessionId: session, agentId: agent, status: phase === 'complete' ? 'SUCCEEDED' : 'RUNNING', mode: 'DIRECT', cancelRequested: false };
    else if (endpoint === `${agentBase}/runs/${runId}/tasks`) body = [{ id: 'task-fixture', title: 'Archive report', taskType: 'DIRECT', status: phase === 'complete' ? 'SUCCEEDED' : recoveryMode ? 'READY' : 'ARCHIVING', workspaceMode: 'RUN_ISOLATED' }];
    else if (endpoint === `${agentBase}/runs/${runId}/recovery`) body = {
      runId, runStatus: phase === 'complete' ? 'SUCCEEDED' : 'RUNNING',
      state: recoveryMode ? phase === 'complete' ? 'SUCCEEDED' : 'WAITING_FOR_WORKER' : 'NOT_RECOVERING',
      recoveryCount: recoveryMode ? 1 : 0, attemptCount: 1, queueSlaExceeded: false, queueWaitSeconds: 10,
    };
    else if (endpoint === `${agentBase}/runs/${runId}/plan`) { status = 404; body = {}; }
    else if (endpoint === `${agentBase}/runs/${runId}/events`) body = [];
    else if (endpoint === `${agentBase}/runs/${runId}/artifacts`) body = [];
    else if (endpoint === `${agentBase}/sessions/${session}/turns/window`) body = {
      hasMore: false, nextBeforeSeq: null,
      items: [{ id: 'user-fixture', role: 'USER', content: 'Create the report', seq: 1 }, {
        id: 'answer-fixture', role: 'ASSISTANT', content: phase === 'complete' ? 'Final archived answer' : 'Compute finished.', seq: 2,
        artifacts: phase === 'complete' ? [{ path: 'outputs/report.txt', versionId: version, sizeBytes: 12 }] : [],
      }],
    };
    else { errors.push(`Unexpected API request: ${route.request().method()} ${endpoint}`); status = 404; }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  });
  try {
    await page.goto(`${baseUrl}/agents/${agent}/chat?new=fixture`);
    const composer = page.getByPlaceholder('Message Archive Fixture');
    await composer.fill('Create the report');
    await page.getByRole('button', { name: 'Send message', exact: true }).click();
    await page.waitForURL(`**/chat?session=${session}`);
    await page.locator('.chat-header [role="status"]').waitFor();
    if (recoveryMode) await page.getByRole('status').filter({ hasText: 'Waiting for execution resources' }).waitFor();
    else await page.locator('.run-task .run-status--archiving').waitFor();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'No overflow during archive');
    await page.screenshot({ path: path.join(output, `archiving-${viewport.width}.png`), fullPage: true });
    await page.getByRole('button', { name: 'Close run details', exact: true }).click();
    await composer.fill('Draft for next task');
    assert(await page.getByRole('button', { name: 'Send message', exact: true }).isDisabled());
    await page.reload();
    await page.locator('.chat-header [role="status"]').waitFor();
    await composer.fill('Preserved next-task draft');
    assert(await page.getByRole('button', { name: 'Send message', exact: true }).isDisabled());
    phase = 'complete';
    await page.locator('.message-row--assistant .message-content').filter({ hasText: 'Final archived answer' }).waitFor();
    await page.getByTitle('Preview outputs/report.txt', { exact: true }).waitFor();
    assert.equal(await composer.inputValue(), 'Preserved next-task draft');
    assert(await page.getByRole('button', { name: 'Send message', exact: true }).isEnabled());
    assert.equal(executions, 1, 'Waiting/reloading must not replay the task');
    assert.deepEqual(errors, []);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'No overflow after archive');
    await page.screenshot({ path: path.join(output, `completed-${viewport.width}.png`), fullPage: true });
    console.log(`PASS ${viewport.width}x${viewport.height}: ${recoveryMode ? 'worker waiting/recovery' : 'archive'} indicator, reload, automatic answer/files, draft preservation, no replay`);
  } catch (error) {
    console.error('Browser state:', page.url(), errors, await page.locator('body').innerText());
    await page.screenshot({ path: path.join(output, `failure-${viewport.width}.png`), fullPage: true });
    throw error;
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
