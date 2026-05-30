#!/usr/bin/env node
/**
 * Live concurrency smoke: two parallel ai/stream sessions (distinct chatId; optional second JWT).
 *
 * Detects cross-session response leakage (assistant text from session A appearing in session B).
 * Exercises overlapping Tools-loop workers (GetContent) under Studio's per-request ThreadLocals.
 *
 * Usage:
 *   node scripts/test/functional/run-concurrent-chat-sessions.mjs
 *
 * Env:
 *   CRAFTER_STUDIO_URL, CRAFTER_STUDIO_TOKEN (or scripts/.studio-token)
 *   CRAFTER_STUDIO_TOKEN_B  Optional second user JWT (true multi-user); else same token, different chatIds
 *   CHAT_SITE_ID, CHAT_AGENT_ID, CHAT_TURN_TIMEOUT_MS, CHAT_PREVIEW_TOKEN
 *   CONCURRENT_SESSIONS=echo|tools|both  (default both)
 */
import { randomUUID } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadStudioTokenFromRepoFile, runChatStream } from '../lib/sse-chat-stream.mjs';
import { appendEntry, printDetailedReport } from '../lib/run-report.mjs';
import { chatStreamBodyBase } from '../lib/chat-llm-env.mjs';

const DEFAULT_AGENT_ID = '019c7237-478b-7f98-9a5c-87144c3fb010';

function loadTokenB() {
  if (process.env.CRAFTER_STUDIO_TOKEN_B?.trim()) return process.env.CRAFTER_STUDIO_TOKEN_B.trim();
  const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '../../..');
  const tokenFile = join(repoRoot, 'scripts/.studio-token-b');
  try {
    if (!existsSync(tokenFile)) return '';
    const raw = readFileSync(tokenFile, 'utf8');
    const m = raw.match(/^\s*export\s+CRAFTER_STUDIO_TOKEN_B=(['"])([\s\S]*?)\1\s*$/m);
    if (m?.[2]) return m[2];
  } catch {
    // optional file
  }
  return '';
}

function assertNoCrossLeak(session, ownMarker, otherMarker, { requireOwnMarker = true } = {}) {
  const errors = [];
  if (!session.ok) {
    errors.push(`${session.label}: ${session.reason}`);
    return errors;
  }
  const text = session.assistantText || '';
  if (requireOwnMarker && !text.includes(ownMarker)) {
    errors.push(`${session.label}: assistant text missing own marker ${ownMarker}`);
  }
  if (text.includes(otherMarker)) {
    errors.push(`${session.label}: cross-leak — contains other session marker ${otherMarker}`);
  }
  return errors;
}

async function runEchoPair({ baseUrl, siteId, agentId, tokenA, tokenB, timeoutMs, previewToken }) {
  const markerA = `CONCUR-ECHO-A-${randomUUID().slice(0, 8)}`;
  const markerB = `CONCUR-ECHO-B-${randomUUID().slice(0, 8)}`;
  const chatA = randomUUID();
  const chatB = randomUUID();

  const bodyBase = chatStreamBodyBase({
    agentId,
    siteId,
    extra: { enableTools: false, omitTools: true },
  });

  console.log(`… echo pair  chatA=${chatA.slice(0, 8)}… chatB=${chatB.slice(0, 8)}…`);
  console.log(`   markers: ${markerA} | ${markerB}`);

  const [sessionA, sessionB] = await Promise.all([
    runChatStream({
      baseUrl,
      siteId,
      token: tokenA,
      previewToken,
      timeoutMs,
      label: 'echo-session-a',
      body: {
        ...bodyBase,
        chatId: chatA,
        prompt: `Reply with exactly this string and nothing else: ${markerA}`,
      },
    }),
    runChatStream({
      baseUrl,
      siteId,
      token: tokenB,
      previewToken,
      timeoutMs,
      label: 'echo-session-b',
      body: {
        ...bodyBase,
        chatId: chatB,
        prompt: `Reply with exactly this string and nothing else: ${markerB}`,
      },
    }),
  ]);

  return [...assertNoCrossLeak(sessionA, markerA, markerB), ...assertNoCrossLeak(sessionB, markerB, markerA)];
}

async function runToolsPair({ baseUrl, siteId, agentId, tokenA, tokenB, timeoutToken, previewToken }) {
  const timeoutMs = timeoutToken;
  const markerA = `CONCUR-TOOL-A-${randomUUID().slice(0, 8)}`;
  const markerB = `CONCUR-TOOL-B-${randomUUID().slice(0, 8)}`;
  const chatA = randomUUID();
  const chatB = randomUUID();
  const contentPath = process.env.CONCURRENT_CONTENT_PATH || '/site/website/index.xml';

  const bodyBase = chatStreamBodyBase({
    agentId,
    siteId,
    extra: {
      enableTools: true,
      enabledBuiltInTools: ['GetContent'],
      contentPath,
      authoringSurface: 'preview',
    },
  });

  console.log(`… tools pair  chatA=${chatA.slice(0, 8)}… chatB=${chatB.slice(0, 8)}… path=${contentPath}`);

  const [sessionA, sessionB] = await Promise.all([
    runChatStream({
      baseUrl,
      siteId,
      token: tokenA,
      previewToken,
      timeoutMs,
      label: 'tools-session-a',
      body: {
        ...bodyBase,
        chatId: chatA,
        prompt: `Use GetContent only on ${contentPath}. Your reply must include the token ${markerA} and must NOT include ${markerB}. Mention title_t briefly.`,
      },
    }),
    runChatStream({
      baseUrl,
      siteId,
      token: tokenB,
      previewToken,
      timeoutMs,
      label: 'tools-session-b',
      body: {
        ...bodyBase,
        chatId: chatB,
        prompt: `Use GetContent only on ${contentPath}. Your reply must include the token ${markerB} and must NOT include ${markerA}. Mention navLabel briefly.`,
      },
    }),
  ]);

  const errors = [
    ...assertNoCrossLeak(sessionA, markerA, markerB, { requireOwnMarker: false }),
    ...assertNoCrossLeak(sessionB, markerB, markerA, { requireOwnMarker: false }),
  ];

  if (sessionA.ok && !sessionA.telemetry?.toolsStarted?.includes('GetContent')) {
    errors.push('tools-session-a: expected GetContent in tool telemetry');
  }
  if (sessionB.ok && !sessionB.telemetry?.toolsStarted?.includes('GetContent')) {
    errors.push('tools-session-b: expected GetContent in tool telemetry');
  }

  return errors;
}

async function main() {
  const tokenA = loadStudioTokenFromRepoFile('CRAFTER_STUDIO_TOKEN');
  const tokenB = loadTokenB() || tokenA;
  const baseUrl = process.env.CRAFTER_STUDIO_URL || 'http://localhost:8080';
  const siteId = process.env.CHAT_SITE_ID || process.env.INTEGRATION_SITE_ID || 'aiat-2';
  const agentId = (process.env.CHAT_AGENT_ID || '').trim() || DEFAULT_AGENT_ID;
  const timeoutMs = Number(process.env.CHAT_TURN_TIMEOUT_MS || '180000') || 180000;
  const previewToken = process.env.CHAT_PREVIEW_TOKEN || '';
  const mode = (process.env.CONCURRENT_SESSIONS || 'both').trim().toLowerCase();

  if (!tokenA) {
    console.error('Missing CRAFTER_STUDIO_TOKEN (or scripts/.studio-token)');
    process.exit(2);
  }

  const multiUser = tokenB !== tokenA;
  console.log(`Concurrent chat sessions — Studio ${baseUrl} siteId=${siteId} agentId=${agentId}`);
  console.log(`Users: session A JWT ${multiUser ? '(user A)' : '(same token)'} | session B JWT ${multiUser ? '(user B)' : '(same token, different chatId)'}`);
  console.log('');

  const reportFile = process.env.RUN_ALL_REPORT_FILE || '';
  const reportSuite = process.env.RUN_ALL_REPORT_SUITE || 'step6-concurrent-sessions';
  /** @type {import('../lib/run-report.mjs').ReportEntry[]} */
  const reportEntries = [];

  function recordTest(id, status, reason = '', durationMs = null) {
    const row = { suite: reportSuite, id, label: id, status, reason, durationMs };
    reportEntries.push(row);
    if (reportFile) appendEntry(reportFile, row);
  }

  /** @type {string[]} */
  const allErrors = [];

  if (mode === 'echo' || mode === 'both') {
    console.log('======== Echo isolation (omitTools, parallel streams) ========');
    const t0 = Date.now();
    const echoErrors = await runEchoPair({ baseUrl, siteId, agentId, tokenA, tokenB, timeoutMs, previewToken });
    allErrors.push(...echoErrors);
    if (echoErrors.length) {
      recordTest('concurrent-echo-pair', 'fail', echoErrors.join('; '), Date.now() - t0);
    } else {
      recordTest('concurrent-echo-pair', 'pass', '', Date.now() - t0);
    }
    console.log('');
  }

  if (mode === 'tools' || mode === 'both') {
    console.log('======== Tool isolation (parallel GetContent) ========');
    const t0 = Date.now();
    const toolErrors = await runToolsPair({ baseUrl, siteId, agentId, tokenA, tokenB, timeoutToken: timeoutMs, previewToken });
    allErrors.push(...toolErrors);
    if (toolErrors.length) {
      recordTest('concurrent-tools-pair', 'fail', toolErrors.join('; '), Date.now() - t0);
    } else {
      recordTest('concurrent-tools-pair', 'pass', '', Date.now() - t0);
    }
    console.log('');
  }

  if (reportEntries.length) {
    printDetailedReport(reportEntries, {
      title: `Concurrent sessions: ${reportSuite}`,
      groupBySuite: false,
    });
  }

  if (allErrors.length) {
    console.error('concurrent-chat-sessions FAILED:');
    for (const e of allErrors) console.error(`  ❌ ${e}`);
    process.exit(1);
  }

  console.log('concurrent-chat-sessions OK');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
