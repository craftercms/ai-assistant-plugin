/**
 * Parse SSE chat events from run-chat-scenarios into recipe + tool telemetry for assertions.
 */

/** @param {unknown[]} events */
export function summarizeSseTelemetry(events) {
  /** @type {{ recipeId: string, outcome: string, raw: Record<string, unknown> }[]} */
  const recipeRouting = [];
  /** @type {Map<string, Set<string>>} */
  const toolPhases = new Map();
  let completed = false;
  let streamError = null;

  for (const ev of events || []) {
    if (!ev || typeof ev !== 'object') continue;
    const meta = /** @type {Record<string, unknown>} */ (ev.metadata || {});
    if (meta.error) {
      streamError = String(meta.message || meta.detail || 'metadata.error');
    }
    if (meta.completed) completed = true;

    if (meta.status === 'intent-recipe-routing' && meta.intentRecipeRouting instanceof Object) {
      const ir = /** @type {Record<string, unknown>} */ (meta.intentRecipeRouting);
      const recipeId = String(ir.recipeId ?? '').trim();
      const outcome = String(ir.outcome ?? '').trim();
      if (recipeId || outcome) {
        recipeRouting.push({ recipeId, outcome, raw: ir });
      }
    }

    if (meta.status === 'tool-progress') {
      const tool = String(meta.tool ?? '').trim();
      const phase = String(meta.phase ?? '').trim();
      if (tool && tool !== 'Tools-loop chat') {
        if (!toolPhases.has(tool)) toolPhases.set(tool, new Set());
        if (phase) toolPhases.get(tool).add(phase);
      }
    }
  }

  const toolsStarted = [...toolPhases.entries()]
    .filter(([, phases]) => phases.has('start') || phases.has('progress') || phases.has('done'))
    .map(([name]) => name);

  const toolsDone = [...toolPhases.entries()]
    .filter(([, phases]) => phases.has('done'))
    .map(([name]) => name);

  const matchedRecipes = recipeRouting
    .filter((r) => r.outcome === 'matched' && r.recipeId)
    .map((r) => r.recipeId);

  return {
    recipeRouting,
    matchedRecipes,
    toolPhases,
    toolsStarted,
    toolsDone,
    completed,
    streamError,
  };
}

/**
 * @param {ReturnType<typeof summarizeSseTelemetry>} telemetry
 * @param {Record<string, unknown> | undefined | null} expect
 * @returns {{ failures: string[], warnings: string[] }}
 */
export function evaluateExpectations(telemetry, expect) {
  if (!expect || typeof expect !== 'object') return { failures: [], warnings: [] };

  const failures = [];
  const warnings = [];
  const exp = expect;

  if (exp.recipeId != null) {
    const want = String(exp.recipeId).trim();
    const outcome = exp.recipeOutcome != null ? String(exp.recipeOutcome).trim() : 'matched';
    const hit = telemetry.recipeRouting.some(
      (r) => r.recipeId === want && (!outcome || r.outcome === outcome),
    );
    if (!hit) {
      const seen = telemetry.recipeRouting
        .map((r) => `${r.recipeId || '?'}(${r.outcome || '?'})`)
        .join(', ');
      const msg = `expected recipe ${want} outcome=${outcome}; saw [${seen || 'none'}]`;
      if (exp.recipeIdSoft === true && toolsAnySatisfied(telemetry, exp.toolsAny)) {
        warnings.push(`${msg} (soft-pass: expected tool(s) ran)`);
      } else {
        failures.push(msg);
      }
    }
  }

  if (Array.isArray(exp.toolsAny) && exp.toolsAny.length) {
    const want = exp.toolsAny.map((t) => String(t).trim()).filter(Boolean);
    const seen = new Set([...telemetry.toolsStarted, ...telemetry.toolsDone]);
    const ok = want.some((t) => seen.has(t));
    if (!ok) {
      failures.push(`expected at least one tool in [${want.join(', ')}]; saw [${[...seen].join(', ') || 'none'}]`);
    }
  }

  if (Array.isArray(exp.toolsAll) && exp.toolsAll.length) {
    const want = exp.toolsAll.map((t) => String(t).trim()).filter(Boolean);
    const seen = new Set([...telemetry.toolsStarted, ...telemetry.toolsDone]);
    const missing = want.filter((t) => !seen.has(t));
    if (missing.length) {
      failures.push(`expected all tools [${want.join(', ')}]; missing [${missing.join(', ')}]`);
    }
  }

  if (Array.isArray(exp.forbidTools) && exp.forbidTools.length) {
    const forbid = exp.forbidTools.map((t) => String(t).trim()).filter(Boolean);
    const seen = new Set([...telemetry.toolsStarted, ...telemetry.toolsDone]);
    const hit = forbid.filter((t) => seen.has(t));
    if (hit.length) {
      failures.push(`forbid tools [${forbid.join(', ')}]; saw forbidden [${hit.join(', ')}]`);
    }
  }

  if (exp.maxToolStarts != null) {
    const max = Number(exp.maxToolStarts);
    if (Number.isFinite(max) && telemetry.toolsStarted.length > max) {
      failures.push(
        `expected at most ${max} repo tool start(s); saw ${telemetry.toolsStarted.length}: [${telemetry.toolsStarted.join(', ')}]`,
      );
    }
  }

  return { failures, warnings };
}

/** @param {ReturnType<typeof summarizeSseTelemetry>} telemetry @param {unknown} toolsAny */
function toolsAnySatisfied(telemetry, toolsAny) {
  if (!Array.isArray(toolsAny) || !toolsAny.length) return false;
  const want = toolsAny.map((t) => String(t).trim()).filter(Boolean);
  const seen = new Set([...telemetry.toolsStarted, ...telemetry.toolsDone]);
  return want.some((t) => seen.has(t));
}
