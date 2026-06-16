/**
 * JS mirror of AuthoringIntentExecutionPlan heuristics for offline parity tests.
 */

/** @param {string} author */
export function deriveSteps(author) {
  const source = String(author || '').trim();
  if (!source) return [];
  let raw = source.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (raw.length <= 1) {
    const parts = source.split(/\s+then\s+|\s*;\s*/i);
    if (parts.length > 1) raw = parts.map((p) => p.trim()).filter(Boolean);
  }
  return raw;
}

/** @param {string} lower */
export function mentionsExternalLookup(lower) {
  return (
    lower.includes('web search') ||
    lower.includes('search the web') ||
    lower.includes('lookup online') ||
    lower.includes('search for') ||
    lower.includes('search ') ||
    lower.includes('headline') ||
    lower.includes("today's") ||
    lower.includes('current events') ||
    lower.includes('latest news') ||
    lower.includes('top news')
  );
}

/** @param {string} lower */
export function mentionsRepoUpdate(lower) {
  return (
    (lower.includes('update') ||
      lower.includes('edit') ||
      lower.includes('rewrite') ||
      lower.includes('modify') ||
      lower.includes('change')) &&
    (lower.includes('content') ||
      lower.includes('copy') ||
      lower.includes('page') ||
      lower.includes('field'))
  );
}

/** @param {string} lower */
export function mentionsImageStep(lower) {
  const imageNoun =
    lower.includes('image') ||
    lower.includes('photo') ||
    lower.includes('picture') ||
    lower.includes('illustration');
  const imageAction =
    lower.includes('generate') ||
    lower.includes('create') ||
    lower.includes('draw') ||
    lower.includes('make') ||
    lower.includes('update') ||
    lower.includes('attach');
  return (
    (imageNoun && imageAction) ||
    lower.includes('hero image') ||
    lower.includes('hero photo') ||
    lower.includes('hero illustration')
  );
}

/** @param {string} stepText */
export function classifyStepKind(stepText) {
  const lower = String(stepText || '').toLowerCase();
  if (mentionsImageStep(lower)) return 'image_generate';
  if (mentionsRepoUpdate(lower)) return 'repo_update';
  if (mentionsExternalLookup(lower)) return 'external_lookup';
  if (lower.includes('getcontent') || lower.includes('read the page') || lower.includes('what is on this page')) {
    return 'repo_read';
  }
  return 'general';
}

/**
 * @param {string} authorVisible
 * @returns {{ kind: string, authorStep: string }[]}
 */
export function derivePlanRows(authorVisible) {
  const steps = deriveSteps(authorVisible);
  /** @type {{ kind: string, authorStep: string }[]} */
  const rows = [];
  let priorExternalLookup = false;
  for (const step of steps) {
    const lower = step.toLowerCase();
    const needsLookup = mentionsExternalLookup(lower);
    const needsWrite = mentionsRepoUpdate(lower) || mentionsImageStep(lower);
    if (needsLookup && needsWrite && !priorExternalLookup) {
      rows.push({ kind: 'external_lookup', authorStep: step });
      priorExternalLookup = true;
    }
    const kind = classifyStepKind(step);
    rows.push({ kind, authorStep: step });
    if (kind === 'external_lookup') priorExternalLookup = true;
  }
  return rows;
}
