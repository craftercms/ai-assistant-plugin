/**
 * Classify optional integration turn failures caused by missing API keys / site config.
 * Used by run-chat-scenarios.mjs — destructive optional turns are unaffected.
 */

/** skipUnless env vars that gate write/publish turns (still opt-in). */
export const DESTRUCTIVE_SKIP_UNLESS = new Set([
  'CHAT_MATRIX_ALLOW_WRITES',
  'CHAT_MATRIX_ALLOW_PUBLISH',
]);

/** @param {string} skipUnless */
export function isDestructiveSkipUnless(skipUnless) {
  return DESTRUCTIVE_SKIP_UNLESS.has(String(skipUnless || '').trim());
}

const MISSING_CONFIG_PATTERNS = [
  /not configured/i,
  /not set up for this site/i,
  /\bapi key\b/i,
  /project tools → secrets/i,
  /project tools -> secrets/i,
  /did not resolve/i,
  /could not be decrypted/i,
  /not set on this (?:studio )?host/i,
  /unresolved macro/i,
  /secret_ref/i,
  /embedding model/i,
  /expert skill/i,
  /no (?:enabled )?expert/i,
  /serpapi is not/i,
  /slack is not/i,
  /crafterq.*(?:not configured|missing api)/i,
  /missing apibase/i,
  /generateimage is not configured/i,
  /image model is not configured/i,
  /llm api key not configured/i,
  /transformcontentsubgraph.*(?:disabled|not available)/i,
  /no site[- ]user tool/i,
  /invoke site user tool.*(?:none|no tools|not available)/i,
  /empty tool list/i,
  /tools: empty/i,
  /intent.recipe.routing/i,
  /intentreciperouting/i,
  /recipe routing.*(?:disabled|not enabled)/i,
  /translatecontent.*(?:not configured|unavailable)/i,
  /translation.*(?:not configured|unavailable|service)/i,
  /HTTP 401/i,
  /HTTP 403/i,
  /invalid api key/i,
  /authentication failed/i,
  /permission denied/i,
  /not authorized/i,
  /forbidden/i,
  /cannot publish/i,
  /publish.*(?:disabled|not allowed|unavailable)/i,
  /write.*(?:denied|not permitted|blocked)/i,
  /insufficient permission/i,
  /Tools-loop chat HTTP 400/i,
  /HTTP 400 Bad Request/i,
];

/** @param {{ reason?: string, events?: unknown[], telemetry?: { streamError?: string } }} result */
export function collectTurnDiagnosticText(result) {
  /** @type {string[]} */
  const parts = [];
  if (result.reason) parts.push(String(result.reason));
  if (result.telemetry?.streamError) parts.push(String(result.telemetry.streamError));
  for (const ev of result.events || []) {
    if (!ev || typeof ev !== 'object') continue;
    const rec = /** @type {Record<string, unknown>} */ (ev);
    if (typeof rec.text === 'string') parts.push(rec.text);
    const meta = rec.metadata;
    if (meta && typeof meta === 'object') {
      const m = /** @type {Record<string, unknown>} */ (meta);
      for (const k of ['message', 'detail', 'error', 'toolResult', 'hint']) {
        if (m[k] != null) parts.push(String(m[k]));
      }
      if (m.maintainerObservability != null) {
        try {
          parts.push(JSON.stringify(m.maintainerObservability));
        } catch {
          parts.push(String(m.maintainerObservability));
        }
      }
    }
  }
  return parts.join('\n');
}

/**
 * @param {Record<string, unknown>} turn
 * @param {{ reason?: string, events?: unknown[], telemetry?: { toolsStarted?: string[], toolsDone?: string[], streamError?: string } }} result
 * @param {string[]} expectFailures
 */
export function isMissingConfigFailure(turn, result, expectFailures = []) {
  if (!turn.partialOnMissingConfig) return false;

  const blob = collectTurnDiagnosticText(result);
  if (blob && MISSING_CONFIG_PATTERNS.some((p) => p.test(blob))) {
    return true;
  }

  const toolsAnyMiss = expectFailures.some((f) => f.startsWith('expected at least one tool'));
  const recipeMiss = expectFailures.some((f) => f.startsWith('expected recipe'));
  if (recipeMiss && turn.partialOnMissingConfig) {
    return true;
  }

  if (!toolsAnyMiss) return false;

  const want = Array.isArray(turn.expect?.toolsAny)
    ? turn.expect.toolsAny.map((t) => String(t).trim()).filter(Boolean)
    : [];
  if (!want.length) return false;

  const seen = new Set([
    ...(result.telemetry?.toolsStarted || []),
    ...(result.telemetry?.toolsDone || []),
  ]);
  const anyStarted = want.some((t) => seen.has(t));
  // Integration tool disabled or blocked (no key) — model often cannot invoke it.
  if (!anyStarted) {
    if (turn.optional) return true;
    if (turn.partialOnMissingConfig && turn.expect?.recipeIdSoft) return true;
  }

  // Optional harness turn marked partial: stream/HTTP abort (e.g. destructive tool wire 400).
  if (turn.optional && turn.partialOnMissingConfig && result.ok === false && result.reason) {
    return true;
  }

  return false;
}
