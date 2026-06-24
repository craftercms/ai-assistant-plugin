/**
 * Parse SSE chat events from run-chat-scenarios into recipe + tool telemetry for assertions.
 */

/** @param {unknown[]} events */
export function summarizeSseTelemetry(events) {
  /** @type {{ recipeId: string, outcome: string, raw: Record<string, unknown> }[]} */
  const recipeRouting = [];
  /** @type {Map<string, Set<string>>} */
  const toolPhases = new Map();
  /** @type {Map<string, number>} */
  const toolStartCounts = new Map();
  /** @type {string[]} */
  const generateImagePrompts = [];
  /** @type {{ kind: string, markdown: string }[]} */
  const stepBridgeCards = [];
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

    if (meta.status === 'step-bridge-card' && meta.stepBridge instanceof Object) {
      const sb = /** @type {Record<string, unknown>} */ (meta.stepBridge);
      const markdown = String(sb.markdown ?? ev.text ?? '').trim();
      const kind = String(sb.kind ?? '').trim();
      if (markdown) {
        stepBridgeCards.push({ kind, markdown });
      }
    }

    if (meta.status === 'tool-progress') {
      const tool = String(meta.tool ?? '').trim();
      const phase = String(meta.phase ?? '').trim();
      if (tool && tool !== 'Tools-loop chat') {
        if (!toolPhases.has(tool)) toolPhases.set(tool, new Set());
        if (phase) toolPhases.get(tool).add(phase);
        if (phase === 'start') {
          toolStartCounts.set(tool, (toolStartCounts.get(tool) || 0) + 1);
        }
      }
      if (tool === 'GenerateImage' && typeof meta.generateImagePrompt === 'string') {
        const gp = meta.generateImagePrompt.trim();
        if (gp) generateImagePrompts.push(gp);
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

  /** @type {{ turnGoal: string, successCriteria: string, outcome: string, intentCardMarkdown: string }[]} */
  const turnGoals = recipeRouting
    .map((r) => ({
      turnGoal: String(r.raw?.turnGoal ?? '').trim(),
      successCriteria: String(r.raw?.successCriteria ?? '').trim(),
      outcome: r.outcome,
      intentCardMarkdown: String(r.raw?.intentCardMarkdown ?? '').trim(),
    }))
    .filter((g) => g.turnGoal);

  const intentCards = recipeRouting
    .map((r) => String(r.raw?.intentCardMarkdown ?? '').trim())
    .filter(Boolean);

  return {
    recipeRouting,
    matchedRecipes,
    turnGoals,
    intentCards,
    stepBridgeCards,
    toolPhases,
    toolStartCounts,
    generateImagePrompts,
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

  if (exp.forbidRecipeId != null) {
    const forbid = String(exp.forbidRecipeId).trim();
    const matched = telemetry.recipeRouting.some(
      (r) => r.recipeId === forbid && r.outcome === 'matched',
    );
    if (matched) {
      failures.push(`forbid matched recipe ${forbid}; routing should not whole-turn match that recipe`);
    }
  }

  if (exp.deferToPlanLoop === true) {
    const ok =
      telemetry.recipeRouting.some(
        (r) => r.outcome === 'plan' || r.raw?.deferToPlanLoop === true,
      );
    if (!ok) {
      const seen = telemetry.recipeRouting
        .map((r) => `${r.recipeId || '?'}(${r.outcome || '?'})`)
        .join(', ');
      failures.push(`expected deferToPlanLoop / outcome=plan; saw [${seen || 'none'}]`);
    }
  }

  if (exp.planDeferCatalogSent === true) {
    const ok = telemetry.recipeRouting.some((r) => r.raw?.planDeferCatalogSent === true);
    if (!ok) {
      failures.push('expected intentRecipeRouting.planDeferCatalogSent=true; saw none');
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

  if (exp.maxToolStartCounts != null && typeof exp.maxToolStartCounts === 'object') {
    for (const [tool, maxRaw] of Object.entries(exp.maxToolStartCounts)) {
      const max = Number(maxRaw);
      const name = String(tool).trim();
      if (!name || !Number.isFinite(max)) continue;
      const count = telemetry.toolStartCounts?.get(name) || 0;
      if (count > max) {
        failures.push(`expected at most ${max} start(s) for tool ${name}; saw ${count}`);
      }
    }
  }

  if (exp.generateImagePromptSeen === true) {
    const prompts = telemetry.generateImagePrompts || [];
    if (!prompts.length) {
      failures.push('expected GenerateImage SSE metadata generateImagePrompt; saw none');
    }
  }

  if (exp.turnGoalPresent === true) {
    const goals = telemetry.turnGoals || [];
    if (!goals.length) {
      failures.push('expected intentRecipeRouting.turnGoal in SSE telemetry; saw none');
    }
  }

  if (exp.turnGoalContains != null) {
    const needle = String(exp.turnGoalContains).trim();
    const goals = (telemetry.turnGoals || []).map((g) => g.turnGoal);
    const hit = goals.some((g) => g.toLowerCase().includes(needle.toLowerCase()));
    if (!hit) {
      failures.push(
        `expected turnGoal containing "${needle}"; saw [${goals.join(' | ') || 'none'}]`,
      );
    }
  }

  if (exp.intentCardPresent === true) {
    const cards = telemetry.intentCards || [];
    if (!cards.length) {
      failures.push('expected intentRecipeRouting.intentCardMarkdown in SSE telemetry; saw none');
    }
  }

  if (exp.intentCardContains != null) {
    const needle = String(exp.intentCardContains).trim();
    const cards = telemetry.intentCards || [];
    const hit = cards.some((c) => c.toLowerCase().includes(needle.toLowerCase()));
    if (!hit) {
      failures.push(
        `expected intentCardMarkdown containing "${needle}"; saw [${cards.map((c) => c.slice(0, 80)).join(' | ') || 'none'}]`,
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
