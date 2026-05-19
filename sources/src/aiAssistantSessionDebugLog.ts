/**
 * Formats the in-memory session stream capture for debugging (Copy session log).
 * Part A = parsed timeline (intent / phases / errors); Part B = verbatim redacted lines.
 */

const TEXT_PREVIEW_CHARS = 320;

/** Query params that may carry secrets inside URL string values (not only JSON keys). */
const SENSITIVE_URL_QUERY_PARAM_RE =
  /([?&])(token|previewToken|access_token|accessToken|api_key|apikey|authorization|bearer|crafterPreview|sessionId|sessionToken)=([^&\s"'<>]+)/gi;

const DATA_IMAGE_BASE64_PREFIX_RE = /data:image\/[a-z0-9.+-]+;base64,/gi;

const INLINE_IMAGE_OMITTED_NOTE =
  '[inline image omitted from debug log';

/**
 * Replace {@code data:image/...;base64,...} payloads with a short note (keeps analyst signal, drops ciphertext).
 */
function elideDataImageBase64ForSessionLog(raw: string): string {
  if (!raw || raw.indexOf('data:image') < 0) {
    return raw;
  }
  const re = new RegExp(DATA_IMAGE_BASE64_PREFIX_RE.source, 'gi');
  let out = '';
  let pos = 0;
  const len = raw.length;
  let m: RegExpExecArray | null;
  re.lastIndex = 0;
  while (pos < len) {
    re.lastIndex = pos;
    m = re.exec(raw);
    if (!m || m.index == null) {
      out += raw.slice(pos);
      break;
    }
    out += raw.slice(pos, m.index);
    const payloadStart = m.index + m[0].length;
    let i = payloadStart;
    while (i < len) {
      const c = raw.charAt(i);
      if (
        (c >= 'A' && c <= 'Z') ||
        (c >= 'a' && c <= 'z') ||
        (c >= '0' && c <= '9') ||
        c === '+' ||
        c === '/' ||
        c === '=' ||
        c === '\n' ||
        c === '\r' ||
        c === ' ' ||
        c === '\t'
      ) {
        i++;
        continue;
      }
      break;
    }
    const b64Chars = i - payloadStart;
    out += `${INLINE_IMAGE_OMITTED_NOTE} (${b64Chars} base64 chars)]`;
    pos = i;
  }
  return out;
}

/** Tool JSON may carry raw {@code b64_json} — elide the value only. */
function elideLongB64JsonFieldsForSessionLog(raw: string): string {
  if (!raw || raw.indexOf('b64_json') < 0) {
    return raw;
  }
  return raw.replace(
    /("b64_json"\s*:\s*")([A-Za-z0-9+/=\s]{200,})(")/g,
    (_match, prefix: string, payload: string, suffix: string) =>
      `${prefix}${INLINE_IMAGE_OMITTED_NOTE} (${payload.length} b64_json chars)]${suffix}`
  );
}

/**
 * Final assistant SSE is chunked (~48k); middle frames are often base64 continuations without a {@code data:image} prefix.
 */
function elideLikelyBase64ImageSseTextChunk(line: string): string {
  const isoRow = /^(\d{4}-\d{2}-\d{2}T[^\t]+\t)([\s\S]+)$/.exec(line);
  if (!isoRow) {
    return line;
  }
  let json: Record<string, unknown>;
  try {
    json = JSON.parse(isoRow[2].trim()) as Record<string, unknown>;
  } catch {
    return line;
  }
  const text = typeof json.text === 'string' ? json.text : '';
  if (text.length < 800) {
    return line;
  }
  const compact = text.replace(/\s/g, '');
  if (compact.length < 800) {
    return line;
  }
  const b64ish = (compact.match(/[A-Za-z0-9+/=]/g) || []).length;
  if (b64ish / compact.length < 0.92) {
    return line;
  }
  const next = {
    ...json,
    text: `${INLINE_IMAGE_OMITTED_NOTE} (likely image SSE chunk; ${text.length} chars)]`
  };
  return `${isoRow[1]}${JSON.stringify(next)}`;
}

/** Best-effort redaction before copying the raw SSE debug log to the clipboard. */
export function redactSessionLogLineForCopy(s: string): string {
  let out = s;
  out = elideDataImageBase64ForSessionLog(out);
  out = elideLongB64JsonFieldsForSessionLog(out);
  out = elideLikelyBase64ImageSseTextChunk(out);
  return out
    .replace(/("?(authorization|bearer|token|previewToken)"?\s*:\s*)"[^"]+"/gi, '$1"***"')
    .replace(/("?(?:\w*[Bb]earer\w*|[Tt]oken\w*|previewToken)"?\s*:\s*)"[^"]+"/g, '$1"***"')
    .replace(SENSITIVE_URL_QUERY_PARAM_RE, '$1$2=***')
    .replace(/\bBearer\s+[A-Za-z0-9._+\-/=]+/gi, 'Bearer ***');
}

function previewText(s: string, max = TEXT_PREVIEW_CHARS): string {
  const t = redactSessionLogLineForCopy((s || '').trim());
  if (!t) return '(empty)';
  if (t.length <= max) return t;
  return `${t.slice(0, max)}… [+${t.length - max} chars — see VERBATIM]`;
}

type ParsedLine =
  | { kind: 'iso_sse'; iso: string; json: Record<string, unknown> | null; rawLine: string }
  | { kind: 'client_json'; json: Record<string, unknown>; rawLine: string }
  | { kind: 'unknown'; rawLine: string };

function parseLogLine(line: string): ParsedLine {
  /** Raw SSE rows are logged as `{ISO}\\t{json}` from the chat stream hook. */
  const isoRow = /^(\d{4}-\d{2}-\d{2}T[^\t]+)\t([\s\S]+)$/.exec(line);
  if (isoRow) {
    const iso = isoRow[1];
    const rest = isoRow[2].trim();
    try {
      const json = JSON.parse(rest) as Record<string, unknown>;
      return { kind: 'iso_sse', iso, json, rawLine: line };
    } catch {
      return { kind: 'iso_sse', iso, json: null, rawLine: line };
    }
  }
  try {
    const json = JSON.parse(line) as Record<string, unknown>;
    return { kind: 'client_json', json, rawLine: line };
  } catch {
    return { kind: 'unknown', rawLine: line };
  }
}

function buildParsedTimeline(lines: string[]): string {
  const out: string[] = [];
  let turn = 0;
  let pendingAssistantChars = 0;
  let streamIdsLoggedForTurn = false;

  const flushDeltas = () => {
    if (pendingAssistantChars > 0) {
      out.push(
        `  • Assistant model text (aggregated deltas): +${pendingAssistantChars} chars — fragments in VERBATIM`
      );
      pendingAssistantChars = 0;
    }
  };

  for (const line of lines) {
    const parsed = parseLogLine(line);

    if (parsed.kind === 'unknown') {
      flushDeltas();
      out.push(`  • Non-JSON line: ${previewText(parsed.rawLine, 160)}`);
      continue;
    }

    if (parsed.kind === 'client_json') {
      const o = parsed.json;
      const kind = typeof o.kind === 'string' ? o.kind : '';

      if (kind === 'client.sessionReset') {
        flushDeltas();
        out.push('');
        out.push(
          `── Session capture cleared @ ${typeof o.ts === 'string' ? o.ts : '?'} (site=${String(o.siteId ?? '')} agent=${String(o.agentId ?? '')})`
        );
        continue;
      }

      if (kind === 'client.userSend') {
        flushDeltas();
        turn++;
        streamIdsLoggedForTurn = false;
        out.push('');
        out.push(`── Turn ${turn} — CLIENT → SERVER @ ${typeof o.ts === 'string' ? o.ts : '?'}`);
        const ctx = o.context && typeof o.context === 'object' ? (o.context as Record<string, unknown>) : null;
        if (ctx) {
          const bits: string[] = [];
          const keys = [
            'siteId',
            'agentId',
            'llm',
            'llmModel',
            'imageModel',
            'imageGenerator',
            'authoringSurface',
            'omitTools',
            'enableTools',
            'chatId',
            'contentPath',
            'contentTypeId',
            'studioPreviewPageUrl'
          ];
          for (const k of keys) {
            const v = ctx[k];
            if (v != null && String(v).trim() !== '') bits.push(`${k}=${String(v)}`);
          }
          if (bits.length) out.push(`  Request context: ${bits.join(' | ')}`);
        }
        const disp = typeof o.displayText === 'string' ? o.displayText : '';
        const wire = typeof o.wirePrompt === 'string' ? o.wirePrompt : '';
        out.push(`  Bubble text (${disp.length} chars): ${previewText(disp)}`);
        out.push(`  Wire prompt (${wire.length} chars): ${previewText(wire)}`);
        continue;
      }

      if (kind === 'client.streamOutcome') {
        flushDeltas();
        const oc = typeof o.outcome === 'string' ? o.outcome : 'unknown';
        const msg = typeof o.message === 'string' ? o.message : '';
        const et = typeof o.errorType === 'string' ? o.errorType : '';
        out.push(
          `  ◆ CLIENT OUTCOME @ ${typeof o.ts === 'string' ? o.ts : '?'}: ${oc}${et ? ` (${et})` : ''}${msg ? ` — ${previewText(msg, 480)}` : ''}`
        );
        continue;
      }

      flushDeltas();
      out.push(`  • Client event kind=${kind || '(missing)'}`);
      continue;
    }

    // iso_sse
    if (!parsed.json) {
      flushDeltas();
      out.push(`  • SSE @ ${parsed.iso} [payload not JSON]`);
      continue;
    }

    const e = parsed.json;
    const meta = e.metadata && typeof e.metadata === 'object' ? (e.metadata as Record<string, unknown>) : {};
    const text = typeof e.text === 'string' ? e.text : '';

    const terminal = meta.completed === true || meta.error === true;
    const status = meta.status != null ? String(meta.status) : '';
    const phase = meta.phase != null ? String(meta.phase) : '';

      const phaseInteresting =
        status === 'aiassistant-chat-phase' &&
      phase === 'summarizing-results';

    const interesting =
      terminal ||
      meta.planGateFailure === true ||
      status === 'tool-progress' ||
      status === 'tool-workflow-hint' ||
      status === 'intent-recipe-routing' ||
      status === 'pipeline-heartbeat' ||
      phaseInteresting;

    if (!streamIdsLoggedForTurn && (meta.chatId || meta.messageId)) {
      flushDeltas();
      streamIdsLoggedForTurn = true;
      out.push(
        `  • Stream ids (@ ${parsed.iso}): chatId=${meta.chatId ?? '—'} | messageId=${meta.messageId ?? '—'}`
      );
    }

    if (interesting) {
      flushDeltas();
      const bullets: string[] = [];
      if (meta.completed === true) {
        bullets.push('Terminal: completed=true (normal end of SSE)');
      }
      if (meta.error === true)
        bullets.push(`Terminal: error=true — ${previewText(String(meta.message ?? '(no message)'), 240)}`);
      if (terminal) {
        const wall = meta.toolPipelineWallMs;
        const total = meta.toolPipelineTotalSec;
        const task = meta.toolPipelineTaskCompletionSec;
        if (wall != null || total != null || task != null) {
          bullets.push(
            `Pipeline timing: wallMs=${wall ?? '—'} taskSec=${task ?? '—'} totalSec=${total ?? '—'}`
          );
        }
      }
      if (meta.planGateFailure === true) bullets.push('planGateFailure=true — UI may replace assistant output');
      if (status === 'pipeline-heartbeat') {
        bullets.push(
          `pipeline-heartbeat: elapsedSec=${meta.elapsedSec ?? '?'} nextInSec=${meta.nextInSec ?? '?'} hint=${previewText(String(meta.hint ?? ''), 180)}`
        );
      }
      if (status === 'tool-progress' || status === 'tool-workflow-hint') {
        bullets.push(`Tool strip: status=${status} phase=${phase || '—'} tool=${meta.tool ?? '—'}`);
        const oneLine = text.replace(/\s+/g, ' ').trim();
        if (oneLine) bullets.push(`  strip preview: ${previewText(oneLine, 220)}`);
      }
      if (status === 'intent-recipe-routing') {
        const tel =
          meta.intentRecipeRouting && typeof meta.intentRecipeRouting === 'object'
            ? (meta.intentRecipeRouting as Record<string, unknown>)
            : null;
        bullets.push(
          `Intent recipe: outcome=${tel?.outcome ?? '—'} recipeId=${tel?.recipeId ?? '—'} title=${previewText(String(tel?.recipeTitle ?? ''), 80)}`
        );
        if (tel?.matchPass != null && String(tel.matchPass).length) {
          bullets.push(`  matchPass=${String(tel.matchPass)}`);
        }
        if (tel?.siteToolRoutingEnabled === true || (typeof tel?.siteToolMatchCount === 'number' && tel.siteToolMatchCount > 0)) {
          const matchedIds = tel.matchedSiteToolIds;
          const idLine = Array.isArray(matchedIds) ? matchedIds.map((x) => String(x)).join(', ') : '';
          bullets.push(
            `  site tool hints: count=${String(tel.siteToolMatchCount ?? '—')}${idLine ? ` ids=${previewText(idLine, 200)}` : ''}`
          );
          if (tel?.competingRecipeId != null && String(tel.competingRecipeId).length) {
            bullets.push(`  competingRecipeId=${String(tel.competingRecipeId)}`);
          }
        }
        if (tel?.deferToPlanLoop === true) {
          bullets.push(
            `Plan defer: catalogSent=${String(tel.planDeferCatalogSent ?? '—')} chars=${tel.planDeferCatalogChars ?? '—'} wiredTools=${tel.planDeferWiredToolCount ?? '—'} siteUserTools=${tel.planDeferSiteUserToolCount ?? '—'} InvokeSiteUserTool=${String(tel.planDeferInvokeSiteUserToolWired ?? '—')} mcp=${String(tel.planDeferMcpClientEnabled ?? '—')}`
          );
          const userIds = tel.planDeferSiteUserToolIds;
          if (Array.isArray(userIds) && userIds.length) {
            const idLine = userIds.map((x) => String(x)).join(', ');
            bullets.push(
              `  site user toolIds${tel.planDeferSiteUserToolIdsTruncated === true ? ' (truncated)' : ''}: ${previewText(idLine, 240)}`
            );
          }
          const wireNames = tel.planDeferWiredToolNames;
          if (Array.isArray(wireNames) && wireNames.length) {
            const wireLine = wireNames.map((x) => String(x)).join(', ');
            bullets.push(
              `  wired tool names${tel.planDeferWiredToolNamesTruncated === true ? ' (truncated)' : ''}: ${previewText(wireLine, 280)}`
            );
          }
        }
        const oneLine = text.replace(/\s+/g, ' ').trim();
        if (oneLine) bullets.push(`  chat line: ${previewText(oneLine, 220)}`);
      }
      if (phaseInteresting) {
        bullets.push(
          'Phase: summarizing-results — orchestration summarizing tool results into final assistant markdown'
        );
      }
      out.push(`  • SSE @ ${parsed.iso}`);
      for (const b of bullets) out.push(`      → ${b}`);
      continue;
    }

    if (text.length) {
      pendingAssistantChars += text.length;
    }
  }

  flushDeltas();
  const body = out.join('\n').trim();
  return body || '(Timeline empty — no recognizable events.)';
}

export function formatSessionLogForDebugCopy(lines: string[]): string {
  const generatedAt = new Date().toISOString();
  const redactedLines = lines.map(redactSessionLogLineForCopy);
  /** Delta char totals use the raw capture so spikes remain visible; VERBATIM uses elided lines. */
  const timeline = buildParsedTimeline(lines);
  const verbatim = redactedLines.join('\n');

  return [
    '==============================================================================',
    'AI ASSISTANT — SESSION DEBUG LOG (for maintainers)',
    `Generated (copy time): ${generatedAt}`,
    '',
    'How to read:',
    '  • TIMELINE — what happened in order (phases, tools, terminal frames, client outcomes).',
    '  • VERBATIM — captured SSE lines (JSON); secrets redacted; inline images replaced with',
    '    “[inline image omitted from debug log (N base64 chars)]” notes; use for grep / repro.',
    '',
    '--- TIMELINE ---',
    timeline,
    '',
    '--- VERBATIM (redacted, chronological) ---',
    verbatim || '(empty)',
    ''
  ].join('\n');
}
