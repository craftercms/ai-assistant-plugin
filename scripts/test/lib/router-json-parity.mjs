/**
 * JavaScript parity helpers for {@code AuthoringIntentRecipeRouter} (offline tests only).
 * Keep in sync with authoring/.../AuthoringIntentRecipeRouter.groovy.
 */

/** @param {string} s */
function stripFences(s) {
  let t = s.trim();
  if (!t.startsWith('```')) {
    return t;
  }
  const nl = t.indexOf('\n');
  if (nl > 0) {
    t = t.substring(nl + 1);
  }
  if (t.endsWith('```')) {
    t = t.substring(0, t.length - 3).trim();
  }
  return t;
}

/** @param {string} raw */
export function extractJsonPayload(raw) {
  let t = stripFences(String(raw ?? '').trim());
  if (!t) {
    return '';
  }
  if (t.startsWith('{')) {
    return t;
  }
  const start = t.indexOf('{');
  const end = t.lastIndexOf('}');
  if (start >= 0 && end > start) {
    return t.substring(start, end + 1).trim();
  }
  return t;
}

/** @param {unknown} raw */
function normalizeMode(raw) {
  const m = String(raw ?? '')
    .trim()
    .toLowerCase();
  if (!m) return '';
  if (m === 'chat' || m === 'chat-only' || m === 'no_tools') return 'chat_only';
  if (m === 'recipes' || m === 'workflow') return 'recipe';
  if (m === 'tools' || m === 'single_tool') return 'tool';
  if (m === 'plan' || m === 'orchestrate' || m === 'multi_step') return 'plan';
  if (m === 'chat_only' || m === 'recipe' || m === 'tool' || m === 'plan') return m;
  return '';
}

/** @param {unknown} c */
function parseConfidence(c) {
  try {
    if (typeof c === 'number' && Number.isFinite(c)) {
      return Math.min(1, Math.max(0, c));
    }
    if (c != null) {
      const n = Number.parseFloat(String(c).trim());
      if (Number.isFinite(n)) {
        return Math.min(1, Math.max(0, n));
      }
    }
  } catch {
    /* ignore */
  }
  return 0;
}

/** @param {string | null | undefined} v */
function nullIfEmpty(v) {
  const s = String(v ?? '').trim();
  if (!s || s.toLowerCase() === 'null') return null;
  return s;
}

/**
 * Parses router LLM JSON (parity with Groovy {@code parseRouterJson}).
 * @param {string} raw
 */
export function parseRouterJson(raw) {
  const t = extractJsonPayload(raw);
  if (!t) {
    return {
      mode: 'plan',
      recipeId: null,
      toolName: null,
      confidence: 0,
      reason: 'empty router reply',
      turnGoal: null,
      successCriteria: null,
    };
  }
  try {
    const m = JSON.parse(t);
    if (!m || typeof m !== 'object' || Array.isArray(m)) {
      return {
        mode: 'plan',
        recipeId: null,
        toolName: null,
        confidence: 0,
        reason: 'router reply not a JSON object',
        turnGoal: null,
        successCriteria: null,
      };
    }
    let mode = normalizeMode(m.mode);
    let rid = nullIfEmpty(m.recipeId);
    let toolName = nullIfEmpty(m.toolName);
    const conf = parseConfidence(m.confidence);
    let reason = String(m.reason ?? '').trim();
    const turnGoal = nullIfEmpty(m.turnGoal);
    const successCriteria = nullIfEmpty(m.successCriteria);

    if (!mode) mode = 'plan';
    if (mode === 'recipe' && !rid) {
      mode = 'plan';
      reason = reason || 'mode recipe but recipeId null';
    }
    if (mode === 'tool' && !toolName) {
      mode = 'plan';
      reason = reason || 'mode tool but toolName null';
    }

    return { mode, recipeId: rid, toolName, confidence: conf, reason, turnGoal, successCriteria };
  } catch (e) {
    return {
      mode: 'plan',
      recipeId: null,
      toolName: null,
      confidence: 0,
      reason: `parse error: ${e instanceof Error ? e.message : String(e)}`,
      turnGoal: null,
      successCriteria: null,
    };
  }
}
