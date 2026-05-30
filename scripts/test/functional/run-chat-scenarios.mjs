#!/usr/bin/env node
/**
 * Plugin **functionality** smoke: scripted user prompts against
 * POST /studio/api/2/plugin/script/.../ai/stream (SSE), same contract as sources/src/aiAssistantApi.ts streamChat.
 *
 * LLM backend (OpenAI, proxy, mock gateway, etc.) is whatever Studio/JVM is configured for — this script only drives HTTP.
 *
 * Requires: Node 18+, live Studio, CRAFTER_STUDIO_TOKEN (env or scripts/.studio-token).
 * **agentId:** `CHAT_AGENT_ID`, scenario `defaults.agentId`, first chat row `agentId` in
 * `config/studio/ai-assistant/agents.json` (sandbox content API), else default UUID (`AI_ASSISTANT_DEFAULT_AGENT_ID`).
 *
 * Does not assert exact LLM wording; asserts HTTP 200 and stream completion (`metadata.completed`), or fails on
 * `metadata.error` / incomplete stream.
 *
 * Optional per-turn assertions (`turn.expect`):
 *   recipeId, recipeOutcome (default matched), toolsAny, toolsAll, forbidTools, maxToolStarts,
 *   maxToolStartCounts ({ toolName: maxStarts }), generateImagePromptSeen
 * Optional turn flags: optional, skipUnless (destructive opt-in env var), partialOnMissingConfig,
 * freshChat, group (filter via CHAT_SCENARIO_GROUP).
 *
 * Usage (repo root):
 *   node scripts/test/functional/run-chat-scenarios.mjs [path/to/scenarios.json]
 *   (JWT from CRAFTER_STUDIO_TOKEN or scripts/.studio-token — same as install-plugin.sh)
 *
 * Invoked by `./scripts/test/run-all.sh` step 4 when Studio is live (opt out: `RUN_ALL_SKIP_CHAT_SCENARIOS=1`).
 *
 * Env:
 *   CHAT_SITE_ID          Override defaults.siteId
 *   CHAT_AGENT_ID         Force agent id (optional if discoverable from agents.json or default UUID works)
 *   CHAT_PREVIEW_TOKEN    crafterPreview cookie (recommended for translate / GetPreviewHtml tools)
 *   CHAT_TURN_TIMEOUT_MS  Per-turn wall clock (default 180000)
  CHAT_SCENARIO_GROUP   Run only turns with matching group (intent-recipes | builtin-tools)
  CHAT_LLM              Override defaults.llm for every turn (e.g. claude)
  CHAT_LLM_MODEL        Override defaults.llmModel for every turn
  CHAT_INTER_TURN_DELAY_MS  Pause between turns (default 45000 when CHAT_LLM=claude)
  CHAT_RATE_LIMIT_RETRIES   Retries on Anthropic 429 (default 2 when CHAT_LLM=claude)
  CHAT_RATE_LIMIT_BACKOFF_MS  Backoff before retry (default 65000)
  CHAT_SKIP_OPTIONAL    When 1, skip turns marked optional (integration optional still run when 0)
  CHAT_MATRIX_FULL      When 1, run write/publish optional turns (run-all sets this by default)
 */

import { existsSync, readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateExpectations, summarizeSseTelemetry } from '../lib/sse-telemetry.mjs';
import { isDestructiveSkipUnless, isMissingConfigFailure } from '../lib/partial-failure.mjs';
import { appendEntry, printDetailedReport } from '../lib/run-report.mjs';
import {
  applyChatLlmEnvToDefaults,
  isRateLimitReason,
  resolveInterTurnDelayMs,
  resolveRateLimitBackoffMs,
  resolveRateLimitRetries,
  sleepMs,
} from '../lib/chat-llm-env.mjs';
import { basename } from 'node:path';

/** Same as scripts/lib/studio-auth.sh → scripts/.studio-token (gitignored). */
function loadStudioTokenFromRepoFile() {
  if (process.env.CRAFTER_STUDIO_TOKEN?.trim()) return;
  const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '../../..');
  const tokenFile = join(repoRoot, 'scripts/.studio-token');
  if (!existsSync(tokenFile)) return;
  const raw = readFileSync(tokenFile, 'utf8');
  const m = raw.match(/^\s*export\s+CRAFTER_STUDIO_TOKEN=(['"])([\s\S]*?)\1\s*$/m);
  if (m?.[2]) process.env.CRAFTER_STUDIO_TOKEN = m[2];
}

/** Same value as {@link AI_ASSISTANT_DEFAULT_AGENT_ID} in sources/src/agentConfig.ts */
const DEFAULT_AGENT_ID = '019c7237-478b-7f98-9a5c-87144c3fb010';

function usage(code = 0) {
  const msg = `run-chat-scenarios.mjs — SSE chat turns against Studio ai/stream

Usage:
  node scripts/test/functional/run-chat-scenarios.mjs [path/to/scenarios.json]

Env (required unless in JSON defaults):
  CRAFTER_STUDIO_URL     Base URL (default http://localhost:8080)
  CRAFTER_STUDIO_TOKEN   Bearer JWT (or scripts/.studio-token)

Agent id (first match wins):
  CHAT_AGENT_ID          Optional explicit id
  (else defaults.agentId in the scenario JSON if set and not a placeholder)
  (else first agentId from config/studio/ai-assistant/agents.json)
  (else default UUID — same as AI_ASSISTANT_DEFAULT_AGENT_ID in agentConfig.ts)

Optional:
  CHAT_SITE_ID           Site id (else scenarios.defaults.siteId)
  CHAT_PREVIEW_TOKEN     Preview cookie for tool calls
  CHAT_TURN_TIMEOUT_MS   Ms per turn (default 180000)
`;
  if (code) console.error(msg);
  else console.log(msg);
  process.exit(code);
}

function feedSseBuffer(buffer, chunk, t0, state) {
  buffer = (buffer + chunk).replace(/\r\n/g, '\n');
  while (true) {
    const sep = buffer.indexOf('\n\n');
    if (sep === -1) break;
    const block = buffer.slice(0, sep);
    buffer = buffer.slice(sep + 2);
    for (const line of block.split('\n')) {
      const t = line.trim();
      if (!t.startsWith('data:')) continue;
      const payload = t.slice(5).trim();
      if (!payload || payload === '[DONE]') continue;
      try {
        const ev = JSON.parse(payload);
        state.events.push(ev);
        if (state.firstTokenMs == null && t0 != null) state.firstTokenMs = Date.now() - t0;
        const meta = ev?.metadata;
        if (meta?.error) {
          state.err = true;
          state.errMsg = String(meta.message || meta.detail || 'metadata.error');
        }
        if (meta?.completed) state.completed = true;
      } catch {
        // ignore
      }
    }
  }
  return buffer;
}

/** Extract configuration XML/text from Studio get_configuration JSON (shapes vary by version). */
function configurationXmlFromGetConfigurationBody(j) {
  if (!j || typeof j !== 'object') return null;
  const code = j.response?.code;
  if (code !== undefined && code !== null && Number(code) !== 0) return null;
  const r = j.result;
  if (typeof r === 'string') return r;
  if (r && typeof r === 'object') {
    if (typeof r.content === 'string') return r.content;
    if (typeof r.xml === 'string') return r.xml;
    if (typeof r.configuration === 'string') return r.configuration;
  }
  if (typeof j.content === 'string') return j.content;
  return null;
}

const CENTRAL_AGENTS_SANDBOX_PATH = '/config/studio/ai-assistant/agents.json';

function firstChatAgentIdFromCatalog(file) {
  if (!file || !Array.isArray(file.agents)) return null;
  for (const row of file.agents) {
    if (!row || typeof row !== 'object') continue;
    const mode = String(row.mode ?? '').trim().toLowerCase();
    if (mode === 'autonomous') continue;
    const id = String(row.agentId ?? row.id ?? '').trim();
    if (id) return id;
  }
  return null;
}

async function fetchFirstAgentIdFromAgentsJson(baseUrl, siteId, token) {
  const url = `${baseUrl.replace(/\/$/, '')}/studio/api/2/content/sandbox_items_by_path`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      siteId: String(siteId),
      paths: [CENTRAL_AGENTS_SANDBOX_PATH],
      preferContent: true,
    }),
  });
  const text = await res.text();
  if (!res.ok) {
    console.error(`sandbox_items_by_path agents.json: HTTP ${res.status} ${text.slice(0, 400)}`);
    return null;
  }
  let j;
  try {
    j = JSON.parse(text);
  } catch {
    console.error('sandbox_items_by_path agents.json: response was not JSON');
    return null;
  }
  const resp = j.response ?? j;
  const items = resp?.items;
  if (!Array.isArray(items) || !items.length) return null;
  const item = items[0];
  const blob =
    item?.contentAsString ??
    item?.content ??
    (typeof item?.contentAsString === 'string' ? item.contentAsString : null);
  if (!blob || typeof blob !== 'string') return null;
  try {
    const file = JSON.parse(blob);
    return firstChatAgentIdFromCatalog(file);
  } catch {
    console.error('agents.json: could not parse JSON body');
    return null;
  }
}

async function resolveAgentId({ baseUrl, siteId, token, defaults }) {
  const fromEnv = (process.env.CHAT_AGENT_ID || '').trim();
  if (fromEnv) return fromEnv;
  const fromJson = String(defaults.agentId || '').trim();
  if (fromJson && !fromJson.includes('REPLACE')) return fromJson;
  const fromCatalog = await fetchFirstAgentIdFromAgentsJson(baseUrl, siteId, token);
  if (fromCatalog) {
    console.log(`Resolved agentId from agents.json: ${fromCatalog}`);
    return fromCatalog;
  }
  console.log(
    `No chat agent id in agents.json; using default agent id (${DEFAULT_AGENT_ID}) — same as AI_ASSISTANT_DEFAULT_AGENT_ID in agentConfig.ts.`,
  );
  return DEFAULT_AGENT_ID;
}

async function runTurnWithRetries(args, maxRetries) {
  let last;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    if (attempt > 0) {
      const backoff = resolveRateLimitBackoffMs();
      console.log(`  ⏳ rate-limit retry ${attempt}/${maxRetries} after ${backoff}ms…`);
      await sleepMs(backoff);
    }
    last = await runTurn(args);
    if (last.ok || !isRateLimitReason(last.reason)) {
      return last;
    }
  }
  return last;
}

async function runTurn({ baseUrl, siteId, token, previewToken, body, timeoutMs }) {
  const enc = encodeURIComponent(siteId);
  const url = `${baseUrl.replace(/\/$/, '')}/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=${enc}`;
  const merged = { ...body };
  if (previewToken && String(previewToken).trim()) merged.previewToken = String(previewToken).trim();

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  const t0 = Date.now();
  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      signal: ctrl.signal,
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(merged),
    });
  } catch (e) {
    clearTimeout(timer);
    const msg = e instanceof Error ? e.message : String(e);
    return { ok: false, httpStatus: 0, reason: `Fetch failed: ${msg}`, ms: Date.now() - t0 };
  }

  if (!res.ok) {
    clearTimeout(timer);
    const text = await res.text();
    return {
      ok: false,
      httpStatus: res.status,
      reason: `HTTP ${res.status}: ${text.slice(0, 600)}`,
      ms: Date.now() - t0,
    };
  }

  if (!res.body) {
    clearTimeout(timer);
    return { ok: false, httpStatus: res.status, reason: 'No response body (streaming not supported)', ms: Date.now() - t0 };
  }

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  const state = { events: [], completed: false, err: false, errMsg: '', firstTokenMs: null };
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf = feedSseBuffer(buf, dec.decode(value, { stream: true }), t0, state);
      if (state.err || state.completed) {
        await reader.cancel();
        break;
      }
    }
    buf = feedSseBuffer(buf, dec.decode(), t0, state);
  } finally {
    clearTimeout(timer);
  }

  const telemetry = summarizeSseTelemetry(state.events);

  if (state.err) {
    return {
      ok: false,
      httpStatus: res.status,
      reason: `Stream error: ${state.errMsg}`,
      ms: Date.now() - t0,
      firstTokenMs: state.firstTokenMs,
      events: state.events,
      telemetry,
    };
  }
  if (!state.completed) {
    return {
      ok: false,
      httpStatus: res.status,
      reason: 'Stream ended without metadata.completed',
      ms: Date.now() - t0,
      firstTokenMs: state.firstTokenMs,
      events: state.events,
      telemetry,
    };
  }
  return {
    ok: true,
    httpStatus: res.status,
    ms: Date.now() - t0,
    firstTokenMs: state.firstTokenMs,
    eventCount: state.events.length,
    events: state.events,
    telemetry,
  };
}

function shouldSkipTurn(turn) {
  if (process.env.CHAT_SKIP_OPTIONAL === '1' && turn.optional) {
    return 'optional (CHAT_SKIP_OPTIONAL=1)';
  }
  const skipUnless = String(turn.skipUnless || '').trim();
  if (skipUnless && !process.env[skipUnless] && isDestructiveSkipUnless(skipUnless)) {
    if (process.env.CHAT_MATRIX_FULL !== '1') {
      return `skipUnless ${skipUnless} not set (destructive opt-in; set CHAT_MATRIX_FULL=1 for run-all)`;
    }
  }
  const groupFilter = String(process.env.CHAT_SCENARIO_GROUP || '').trim();
  if (groupFilter && turn.group && turn.group !== groupFilter) {
    return `group ${turn.group} != ${groupFilter}`;
  }
  return null;
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--help') || argv.includes('-h')) usage(0);

  const scenarioPath =
    argv.find((a) => !a.startsWith('-')) ||
    new URL('../scenarios/chat-scenarios.example.json', import.meta.url).pathname;

  const raw = readFileSync(scenarioPath, 'utf8');
  const doc = JSON.parse(raw);
  const defaults = doc.defaults || {};
  const { llmOverride, llmModelOverride } = applyChatLlmEnvToDefaults(defaults);
  const interTurnDelayMs = resolveInterTurnDelayMs(llmOverride);
  const rateLimitRetries = resolveRateLimitRetries(llmOverride);
  const turns = doc.turns || [];

  loadStudioTokenFromRepoFile();

  const baseUrl = process.env.CRAFTER_STUDIO_URL || 'http://localhost:8080';
  const token = process.env.CRAFTER_STUDIO_TOKEN || '';
  const siteId = process.env.CHAT_SITE_ID || defaults.siteId || '';
  const previewToken = process.env.CHAT_PREVIEW_TOKEN || '';
  const timeoutMs = Number(process.env.CHAT_TURN_TIMEOUT_MS || '180000') || 180000;

  if (!token) {
    console.error('Missing CRAFTER_STUDIO_TOKEN');
    usage(2);
  }
  if (!siteId) {
    console.error('Set CHAT_SITE_ID or defaults.siteId in the scenario file.');
    process.exit(2);
  }

  const agentId = await resolveAgentId({ baseUrl, siteId, token, defaults });
  if (!agentId) {
    console.error('Could not resolve agent id.');
    process.exit(2);
  }

  let chatId = randomUUID();
  console.log(`Scenarios: ${scenarioPath}`);
  console.log(`Studio: ${baseUrl}  siteId=${siteId}  agentId=${agentId}  chatId=${chatId}`);
  if (process.env.CHAT_SCENARIO_GROUP) {
    console.log(`Group filter: ${process.env.CHAT_SCENARIO_GROUP}`);
  }
  if (llmOverride || llmModelOverride) {
    console.log(
      `LLM override: llm=${defaults.llm || '(unset)'} llmModel=${defaults.llmModel || '(unset)'}`,
    );
    if (interTurnDelayMs > 0) {
      console.log(`Claude pacing: inter-turn delay=${interTurnDelayMs}ms retries=${rateLimitRetries}`);
    }
  }
  console.log('');

  const reportFile = process.env.RUN_ALL_REPORT_FILE || '';
  const scenarioBase = basename(scenarioPath);
  const reportSuite =
    process.env.RUN_ALL_REPORT_SUITE ||
    (process.env.CHAT_SCENARIO_GROUP
      ? `chat/${process.env.CHAT_SCENARIO_GROUP}`
      : `chat/${scenarioBase.replace(/\.json$/, '')}`);

  /** @type {import('../lib/run-report.mjs').ReportEntry[]} */
  const reportEntries = [];

  function recordTurn(entry) {
    const row = { suite: reportSuite, ...entry };
    reportEntries.push(row);
    if (reportFile) appendEntry(reportFile, row);
  }

  let failed = 0;
  let partial = 0;
  let skipped = 0;
  let passed = 0;
  let executedTurnIndex = 0;
  for (const turn of turns) {
    const id = turn.id || '(no id)';
    const turnLabel = turn.summary || turn.prompt?.slice(0, 80) || '';
    const skipReason = shouldSkipTurn(turn);
    if (skipReason) {
      skipped++;
      recordTurn({ id, label: turnLabel, status: 'skip', reason: skipReason });
      console.log(`⏭ ${id}: skipped (${skipReason})`);
      console.log('');
      continue;
    }

    if (turn.freshChat) {
      chatId = randomUUID();
      console.log(`  (fresh chatId=${chatId})`);
    }

    const body = {
      ...defaults,
      ...(turn.request || {}),
      agentId,
      siteId,
      chatId,
      prompt: turn.prompt != null ? String(turn.prompt) : '',
    };
    const label = `${id}: ${turn.summary || turn.prompt?.slice(0, 60) || ''}`;
    if (executedTurnIndex > 0 && interTurnDelayMs > 0) {
      console.log(`  ⏳ inter-turn delay ${interTurnDelayMs}ms…`);
      await sleepMs(interTurnDelayMs);
    }
    executedTurnIndex++;
    process.stdout.write(`… ${label}\n`);
    const tStart = Date.now();
    try {
      const r = await runTurnWithRetries(
        { baseUrl, siteId, token, previewToken, body, timeoutMs },
        rateLimitRetries,
      );
      const wall = Date.now() - tStart;
      if (r.ok) {
        const { failures: expectFailures, warnings: expectWarnings } = evaluateExpectations(
          r.telemetry,
          turn.expect,
        );
        for (const w of expectWarnings) {
          console.log(`  ⚠️  ${w}`);
        }
        if (expectFailures.length) {
          const reason = expectFailures.join('; ');
          if (isMissingConfigFailure(turn, r, expectFailures)) {
            partial++;
            recordTurn({
              id,
              label: turnLabel,
              status: 'partial',
              reason: `expectations (missing config/key): ${reason}`,
              durationMs: wall,
            });
            console.log(
              `  🟡 partial (missing config/key): ${reason}  (wall ${wall}ms)`,
            );
          } else {
            failed++;
            recordTurn({
              id,
              label: turnLabel,
              status: 'fail',
              reason: `expectations failed: ${reason}`,
              durationMs: wall,
            });
            console.log(`  ❌ expectations failed: ${reason}  (wall ${wall}ms)`);
          }
          if (r.telemetry?.matchedRecipes?.length) {
            console.log(`     recipes: ${r.telemetry.matchedRecipes.join(', ')}`);
          }
          if (r.telemetry?.toolsStarted?.length) {
            console.log(`     tools: ${r.telemetry.toolsStarted.join(', ')}`);
          }
        } else {
          passed++;
          recordTurn({ id, label: turnLabel, status: 'pass', durationMs: wall });
          const recipeNote =
            r.telemetry?.matchedRecipes?.length ? `  recipes=${r.telemetry.matchedRecipes.join(',')}` : '';
          const toolNote =
            r.telemetry?.toolsStarted?.length ? `  tools=${r.telemetry.toolsStarted.join(',')}` : '';
          console.log(
            `  ✅ completed  total=${r.ms}ms  first-chunk≈${r.firstTokenMs != null ? `${r.firstTokenMs}ms` : 'n/a'}  events=${r.eventCount}${recipeNote}${toolNote}`,
          );
        }
      } else {
        const reason = r.reason || 'stream failed';
        if (isMissingConfigFailure(turn, r, [reason])) {
          partial++;
          recordTurn({
            id,
            label: turnLabel,
            status: 'partial',
            reason: `missing config/key: ${reason}`,
            durationMs: wall,
          });
          console.log(`  🟡 partial (missing config/key): ${reason}  (wall ${wall}ms)`);
        } else {
          failed++;
          recordTurn({ id, label: turnLabel, status: 'fail', reason, durationMs: wall });
          console.log(`  ❌ ${reason}  (wall ${wall}ms)`);
        }
      }
    } catch (e) {
      failed++;
      const reason = e instanceof Error ? e.message : String(e);
      const wall = Date.now() - tStart;
      recordTurn({ id, label: turnLabel, status: 'fail', reason, durationMs: wall });
      console.log(`  ❌ ${reason}  (wall ${wall}ms)`);
    }
    console.log('');
  }

  printDetailedReport(reportEntries, {
    title: `Scenario report: ${reportSuite} (${scenarioBase})`,
    groupBySuite: false,
  });

  console.log(`Summary: passed=${passed} partial=${partial} failed=${failed} skipped=${skipped}`);
  if (partial) {
    console.log(
      `Note: ${partial} optional turn(s) failed due to missing integration config/keys (partial — not a harness regression).`,
    );
  }
  if (failed) {
    console.error(`Done: ${failed} turn(s) failed.`);
    process.exit(1);
  }
  if (partial) {
    console.log('Done: all required turns passed; see partial count for optional integration gaps.');
    process.exit(0);
  }
  console.log('Done: all executed turns completed successfully.');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
