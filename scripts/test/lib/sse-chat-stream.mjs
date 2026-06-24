/**
 * Minimal ai/stream SSE client for functional tests (shared by scenario runners).
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { summarizeSseTelemetry } from './sse-telemetry.mjs';

/** Load CRAFTER_STUDIO_TOKEN from scripts/.studio-token when env unset. */
export function loadStudioTokenFromRepoFile(envVar = 'CRAFTER_STUDIO_TOKEN') {
  if (process.env[envVar]?.trim()) return process.env[envVar].trim();
  const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '../../..');
  const tokenFile = join(repoRoot, 'scripts/.studio-token');
  if (!existsSync(tokenFile)) return '';
  const raw = readFileSync(tokenFile, 'utf8');
  const m = raw.match(/^\s*export\s+CRAFTER_STUDIO_TOKEN=(['"])([\s\S]*?)\1\s*$/m);
  if (m?.[2]) {
    process.env[envVar] = m[2];
    return m[2];
  }
  return '';
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

/** Concatenate assistant-visible text chunks from SSE events. */
export function assistantTextFromEvents(events) {
  let out = '';
  for (const ev of events || []) {
    const text = ev?.text;
    if (typeof text === 'string' && text.trim()) {
      out += text;
    }
  }
  return out;
}

/**
 * POST ai/stream and read until completed or error.
 * @returns {Promise<{ ok: boolean, reason?: string, ms: number, events: unknown[], telemetry: ReturnType<typeof summarizeSseTelemetry>, assistantText: string, chatId?: string }>}
 */
export async function runChatStream({
  baseUrl,
  siteId,
  token,
  previewToken,
  body,
  timeoutMs = 180000,
  label = 'session',
}) {
  const enc = encodeURIComponent(siteId);
  const url = `${baseUrl.replace(/\/$/, '')}/studio/api/2/plugin/script/plugins/org/craftercms/aiassistant/studio/aiassistant/ai/stream?siteId=${enc}`;
  const merged = { ...body };
  if (previewToken?.trim()) merged.previewToken = String(previewToken).trim();

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
    return {
      ok: false,
      label,
      reason: `Fetch failed: ${msg}`,
      ms: Date.now() - t0,
      events: [],
      telemetry: summarizeSseTelemetry([]),
      assistantText: '',
      chatId: body?.chatId,
    };
  }

  if (!res.ok) {
    clearTimeout(timer);
    const text = await res.text();
    return {
      ok: false,
      label,
      reason: `HTTP ${res.status}: ${text.slice(0, 600)}`,
      ms: Date.now() - t0,
      events: [],
      telemetry: summarizeSseTelemetry([]),
      assistantText: '',
      chatId: body?.chatId,
    };
  }

  if (!res.body) {
    clearTimeout(timer);
    return {
      ok: false,
      label,
      reason: 'No response body',
      ms: Date.now() - t0,
      events: [],
      telemetry: summarizeSseTelemetry([]),
      assistantText: '',
      chatId: body?.chatId,
    };
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
  const assistantText = assistantTextFromEvents(state.events);

  if (state.err) {
    return {
      ok: false,
      label,
      reason: `Stream error: ${state.errMsg}`,
      ms: Date.now() - t0,
      firstTokenMs: state.firstTokenMs,
      events: state.events,
      telemetry,
      assistantText,
      chatId: body?.chatId,
    };
  }
  if (!state.completed) {
    return {
      ok: false,
      label,
      reason: 'Stream ended without metadata.completed',
      ms: Date.now() - t0,
      firstTokenMs: state.firstTokenMs,
      events: state.events,
      telemetry,
      assistantText,
      chatId: body?.chatId,
    };
  }

  return {
    ok: true,
    label,
    ms: Date.now() - t0,
    firstTokenMs: state.firstTokenMs,
    eventCount: state.events.length,
    events: state.events,
    telemetry,
    assistantText,
    chatId: body?.chatId,
  };
}
