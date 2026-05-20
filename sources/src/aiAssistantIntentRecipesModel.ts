/**
 * Built-in intent recipe catalog (bundled with the plugin). Studio sites do not need
 * {@code /config/studio/scripts/aiassistant/config/intent-recipes.json} until authors save custom recipes;
 * the configuration UI merges this in-memory catalog with an optional site file.
 */
import bundledCatalog from '../../authoring/scripts/classes/plugins/org/craftercms/aiassistant/recipes/authoring-intent-recipes-default.json';
import { STUDIO_AI_BUILTIN_TOOL_IDS, STUDIO_AI_MCP_ALL_TOKEN } from './studioAiOrchestrationToolIds';

/** Studio {@code studio} module path (tools.json {@code customRecipesPath} default). */
export const INTENT_RECIPES_JSON_REL = 'scripts/aiassistant/config/intent-recipes.json';

/** Sandbox repo path when the site override file exists (optional until first save). */
export const INTENT_RECIPES_JSON_SANDBOX_PATH = `/config/studio/${INTENT_RECIPES_JSON_REL}`;

export const INTENT_RECIPE_PHASE_KEYS = ['context', 'action', 'confirmation'] as const;
export type IntentRecipePhaseKey = (typeof INTENT_RECIPE_PHASE_KEYS)[number];

export type IntentRecipeEngineStep = {
  /** Named prefetch artifact ({@code initial.name} / {@code current.name} at runtime). */
  as?: string;
  tool: string;
  args?: Record<string, string>;
};

export type IntentRecipePhaseBlock = {
  hints?: string[];
  engineSteps?: IntentRecipeEngineStep[];
};

/** Phase may be hint strings only, or a block with hints + deterministic prefetch steps. */
export type IntentRecipePhaseValue = string[] | IntentRecipePhaseBlock;

export type IntentRecipeChatDefaults = {
  prefixEmoji?: string;
  fallbackEmoji?: string;
  lineSuffix?: string;
};

/** Config-driven match rule (see {@code AuthoringIntentRecipeWhen} on the server). */
export type IntentRecipeWhenExpr =
  | string
  | {
      allOf?: IntentRecipeWhenExpr[];
      anyOf?: IntentRecipeWhenExpr[];
      not?: IntentRecipeWhenExpr;
      authorContainsAny?: string[];
      authorContainsNone?: string[];
      authorMatchesRegex?: string | string[];
    };

export type IntentRecipeMatchRule = {
  priority?: number;
  routerReason?: string;
  skipPrefetch?: boolean;
  when?: IntentRecipeWhenExpr;
  requiresAnchoredSiteXml?: boolean;
  requiresNoAnchoredSiteXml?: boolean;
  authorFromMatchHints?: boolean;
  respectDontMatchHints?: boolean;
  authorContainsAny?: string[];
  authorContainsNone?: string[];
  authorMatchesRegex?: string | string[];
};

export type IntentRecipeMatchRules = IntentRecipeMatchRule | IntentRecipeMatchRule[];

export const INTENT_RECIPE_WHEN_LEAF_OPTIONS = [
  'anchoredSiteXml',
  'translateIntent',
  'concreteFieldEdit',
  'externalContentFieldEdit',
  'chatArtifactFollowup',
  'creativeLlmOnly',
  'currentTurnCmsTooling',
  'imageOnlyGenerate'
] as const;

export type IntentRecipe = {
  id: string;
  title?: string;
  /** Workflow-kind emoji in Studio chat when this recipe matches ({@code chatDefaults.fallbackEmoji} if unset). */
  chatEmoji?: string;
  description?: string;
  matchHints?: string[];
  /** Substrings in the author message that disqualify this recipe (case-insensitive). */
  dontMatchHints?: string[];
  /** Server routing: exactly one matching rule per recipe id selects the whole-turn workflow. */
  deterministicMatch?: IntentRecipeMatchRules;
  /** Optional extra candidates when clarify/disambiguation runs (same schema as {@link deterministicMatch}). */
  ambiguityMatch?: IntentRecipeMatchRules;
  phases?: Partial<Record<IntentRecipePhaseKey, IntentRecipePhaseValue>>;
  toolsLoopForceTool?: string;
  toolsLoopDisable?: boolean;
  toolsLoopAllowlist?: string[];
  toolsLoopAllowlistBypassIfAuthorMentions?: string[];
  /** Wire names removed from the session tool list when this recipe matches (e.g. image turns exclude GenerateTextNoTools). */
  toolsLoopExcludeTools?: string[];
  /** Max HTML chars per {@code FetchHttpUrl} on the chat wire for this recipe. */
  toolsLoopFetchHttpUrlWireMaxChars?: number;
  /** Server cap on {@code FetchHttpUrl} calls per turn (web-research recipes). */
  toolsLoopMaxFetchHttpUrlCalls?: number;
  matchedUserPrelude?: string;
};

export type IntentRecipesFile = {
  version: number;
  recipes: IntentRecipe[];
  /** Site-defined catalog order (recipe ids). Shown to the intent-router table; may affect model behavior. */
  recipeOrder?: string[];
  chatDefaults?: IntentRecipeChatDefaults;
};

export type IntentRecipeListEntry = {
  id: string;
  title: string;
  /** Resolved workflow emoji for list + preview (recipe {@code chatEmoji} or catalog fallback). */
  chatEmoji: string;
  source: 'bundled' | 'custom' | 'override';
  recipe: IntentRecipe;
};

/** First user-perceived grapheme (emoji-safe). */
export function normalizeChatEmoji(input: string): string {
  const t = String(input ?? '').trim();
  if (!t) return '';
  try {
    if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
      const seg = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
      const first = [...seg.segment(t)][0]?.segment;
      if (first) return first;
    }
  } catch {
    /* ignore */
  }
  return [...t][0] ?? '';
}

export function resolveRecipeChatEmoji(recipe: IntentRecipe, catalogFallbackEmoji?: string): string {
  const fromRecipe = normalizeChatEmoji(recipe.chatEmoji ?? '');
  if (fromRecipe) return fromRecipe;
  const fb = normalizeChatEmoji(catalogFallbackEmoji ?? '');
  return fb || '📋';
}

/** Read-only tools the server recipe engine may run during prefetch (see AuthoringIntentRecipeEngine). */
/** Wire tool names for hint autocomplete and action-flow chips (excludes {@link STUDIO_AI_MCP_ALL_TOKEN}). */
export const INTENT_RECIPE_WIRE_TOOL_OPTIONS: readonly string[] = STUDIO_AI_BUILTIN_TOOL_IDS.filter(
  (id) => id !== STUDIO_AI_MCP_ALL_TOKEN
);

export const INTENT_RECIPE_READ_ONLY_TOOLS = [
  'GetContent',
  'GetContentTypeFormDefinition',
  'ListContentTranslationScope',
  'ListContentDependencyScope',
  'ListStudioContentTypes',
  'GetContentVersionHistory',
  'GetPreviewHtml'
] as const;

export function defaultIntentRecipesFile(): IntentRecipesFile {
  return { version: 1, recipes: [] };
}

export function parseIntentRecipesFile(raw: string): { ok: true; file: IntentRecipesFile } | { ok: false; message: string } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw || '{}');
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : String(e) };
  }
  if (!parsed || typeof parsed !== 'object') {
    return { ok: false, message: 'Root must be a JSON object.' };
  }
  const o = parsed as Record<string, unknown>;
  const version = typeof o.version === 'number' ? o.version : 1;
  if (!Array.isArray(o.recipes)) {
    return { ok: false, message: 'Missing "recipes" array.' };
  }
  const recipes: IntentRecipe[] = [];
  for (const item of o.recipes) {
    if (!item || typeof item !== 'object') continue;
    const r = item as Record<string, unknown>;
    const id = String(r.id ?? '').trim();
    if (!id) continue;
    recipes.push(r as IntentRecipe);
  }
  const recipeOrder = Array.isArray(o.recipeOrder)
    ? o.recipeOrder.map((x) => String(x ?? '').trim()).filter(Boolean)
    : undefined;
  let chatDefaults: IntentRecipeChatDefaults | undefined;
  if (o.chatDefaults && typeof o.chatDefaults === 'object' && !Array.isArray(o.chatDefaults)) {
    const cd = o.chatDefaults as Record<string, unknown>;
    chatDefaults = {
      ...(cd.prefixEmoji != null ? { prefixEmoji: String(cd.prefixEmoji) } : {}),
      ...(cd.fallbackEmoji != null ? { fallbackEmoji: String(cd.fallbackEmoji) } : {}),
      ...(cd.lineSuffix != null ? { lineSuffix: String(cd.lineSuffix) } : {})
    };
  }
  return {
    ok: true,
    file: {
      version,
      recipes,
      ...(recipeOrder?.length ? { recipeOrder } : {}),
      ...(chatDefaults ? { chatDefaults } : {})
    }
  };
}

export function serializeIntentRecipesFile(file: IntentRecipesFile): string {
  const body: IntentRecipesFile = {
    version: file.version ?? 1,
    recipes: file.recipes ?? []
  };
  if (file.recipeOrder?.length) {
    body.recipeOrder = file.recipeOrder;
  }
  if (file.chatDefaults && Object.keys(file.chatDefaults).length > 0) {
    body.chatDefaults = file.chatDefaults;
  }
  return JSON.stringify(body, null, 2);
}

export function bundledIntentRecipesCatalog(): IntentRecipesFile {
  const cat = bundledCatalog as IntentRecipesFile;
  const recipeOrder = Array.isArray(cat.recipeOrder)
    ? cat.recipeOrder.map((x) => String(x ?? '').trim()).filter(Boolean)
    : undefined;
  return {
    version: typeof cat.version === 'number' ? cat.version : 1,
    recipes: Array.isArray(cat.recipes) ? (cat.recipes as IntentRecipe[]) : [],
    ...(recipeOrder?.length ? { recipeOrder } : {}),
    ...(cat.chatDefaults ? { chatDefaults: cat.chatDefaults } : {})
  };
}

/** Same merge semantics as {@code AuthoringIntentRecipeCatalog.loadRecipes}: bundled first, site overrides by id. */
export function mergeIntentRecipeCatalog(bundled: IntentRecipe[], custom: IntentRecipe[]): IntentRecipe[] {
  const byId = new Map<string, IntentRecipe>();
  for (const r of bundled) {
    const id = String(r?.id ?? '').trim();
    if (id) byId.set(id, r);
  }
  for (const r of custom) {
    const id = String(r?.id ?? '').trim();
    if (id) byId.set(id, r);
  }
  return [...byId.values()];
}

/** Apply saved id order; unknown ids keep stable relative order at the end. */
export function orderIntentRecipes(merged: IntentRecipe[], recipeOrder?: string[]): IntentRecipe[] {
  if (!recipeOrder?.length) {
    return merged;
  }
  const byId = new Map<string, IntentRecipe>();
  for (const r of merged) {
    const id = String(r.id ?? '').trim();
    if (id) byId.set(id, r);
  }
  const out: IntentRecipe[] = [];
  const seen = new Set<string>();
  for (const id of recipeOrder) {
    const r = byId.get(id);
    if (r) {
      out.push(r);
      seen.add(id);
    }
  }
  for (const r of merged) {
    const id = String(r.id ?? '').trim();
    if (id && !seen.has(id)) {
      out.push(r);
    }
  }
  return out;
}

export function defaultRecipeOrderForCatalog(bundled: IntentRecipe[], custom: IntentRecipe[]): string[] {
  return orderIntentRecipes(mergeIntentRecipeCatalog(bundled, custom)).map((r) => String(r.id ?? '').trim()).filter(Boolean);
}

export function listIntentRecipeEntries(
  bundled: IntentRecipe[],
  custom: IntentRecipe[],
  recipeOrder?: string[],
  chatDefaults?: IntentRecipeChatDefaults
): IntentRecipeListEntry[] {
  const bundledIds = new Set(bundled.map((r) => String(r.id ?? '').trim()).filter(Boolean));
  const customIds = new Set(custom.map((r) => String(r.id ?? '').trim()).filter(Boolean));
  const merged = orderIntentRecipes(mergeIntentRecipeCatalog(bundled, custom), recipeOrder);
  const catalogFallback = chatDefaults?.fallbackEmoji;
  return merged.map((recipe) => {
    const id = String(recipe.id ?? '').trim();
    const title = String(recipe.title ?? id).trim() || id;
    let source: IntentRecipeListEntry['source'] = 'bundled';
    if (customIds.has(id) && bundledIds.has(id)) source = 'override';
    else if (customIds.has(id)) source = 'custom';
    return {
      id,
      title,
      chatEmoji: resolveRecipeChatEmoji(recipe, catalogFallback),
      source,
      recipe
    };
  });
}

export function normalizePhaseValue(raw: unknown): IntentRecipePhaseValue | null {
  if (raw == null) return null;
  if (Array.isArray(raw)) {
    return raw.map((x) => String(x ?? '')).filter(Boolean);
  }
  if (typeof raw === 'object') {
    const o = raw as Record<string, unknown>;
    const block: IntentRecipePhaseBlock = {};
    if (Array.isArray(o.hints)) {
      block.hints = o.hints.map((x) => String(x ?? '')).filter(Boolean);
    }
    if (Array.isArray(o.engineSteps)) {
      block.engineSteps = o.engineSteps
        .map((step) => {
          if (!step || typeof step !== 'object') return null;
          const s = step as Record<string, unknown>;
          const tool = String(s.tool ?? '').trim();
          if (!tool) return null;
          const args =
            s.args && typeof s.args === 'object'
              ? Object.fromEntries(
                  Object.entries(s.args as Record<string, unknown>).map(([k, v]) => [k, String(v ?? '')])
                )
              : undefined;
          const asName = String(s.as ?? '').trim();
          return {
            ...(asName ? { as: asName } : {}),
            tool,
            args
          } satisfies IntentRecipeEngineStep;
        })
        .filter((x): x is IntentRecipeEngineStep => x != null);
    }
    if ((block.hints?.length ?? 0) === 0 && (block.engineSteps?.length ?? 0) === 0) return null;
    return block;
  }
  return null;
}

/** Binding names from prefetch step {@code as} fields (server: {@code AuthoringIntentRecipeBindings}). */
export function declaredBindingNames(recipe: IntentRecipe): string[] {
  const names = new Set<string>();
  for (const step of collectEngineStepsFromRecipe(recipe)) {
    const as = String(step.as ?? '').trim();
    if (as) names.add(as);
  }
  return [...names];
}

export function collectEngineStepsFromRecipe(recipe: IntentRecipe): IntentRecipeEngineStep[] {
  const out: IntentRecipeEngineStep[] = [];
  const phases = recipe.phases;
  if (!phases) return out;
  for (const key of INTENT_RECIPE_PHASE_KEYS) {
    const phase = normalizePhaseValue(phases[key]);
    if (phase && !Array.isArray(phase) && phase.engineSteps?.length) {
      out.push(...phase.engineSteps);
    }
  }
  return out;
}

export function phaseHints(recipe: IntentRecipe, key: IntentRecipePhaseKey): string[] {
  const phase = normalizePhaseValue(recipe.phases?.[key]);
  if (!phase) return [];
  if (Array.isArray(phase)) return phase;
  return phase.hints ?? [];
}

export function phaseEngineSteps(recipe: IntentRecipe, key: IntentRecipePhaseKey): IntentRecipeEngineStep[] {
  const phase = normalizePhaseValue(recipe.phases?.[key]);
  if (!phase || Array.isArray(phase)) return [];
  return phase.engineSteps ?? [];
}

export function cloneRecipeForCustom(recipe: IntentRecipe, newId?: string): IntentRecipe {
  return JSON.parse(JSON.stringify({ ...recipe, id: newId ?? `${recipe.id}_custom` })) as IntentRecipe;
}

export function cloneRecipe(recipe: IntentRecipe): IntentRecipe {
  return JSON.parse(JSON.stringify(recipe)) as IntentRecipe;
}

export function cloneIntentRecipesFile(file: IntentRecipesFile): IntentRecipesFile {
  return {
    version: file.version,
    recipes: file.recipes.map((r) => cloneRecipe(r)),
    ...(file.recipeOrder?.length ? { recipeOrder: [...file.recipeOrder] } : {}),
    ...(file.chatDefaults ? { chatDefaults: { ...file.chatDefaults } } : {})
  };
}

export function emptyRecipe(id: string): IntentRecipe {
  return {
    id: id.trim() || 'new_recipe',
    title: 'New intent recipe',
    chatEmoji: '📋',
    description: '',
    matchHints: [],
    phases: {
      context: { hints: [], engineSteps: [] },
      action: [],
      confirmation: []
    }
  };
}

export function validateRecipe(recipe: IntentRecipe): { ok: true } | { ok: false; message: string } {
  const id = String(recipe.id ?? '').trim();
  if (!id) return { ok: false, message: 'Recipe id is required.' };
  if (!/^[a-z][a-z0-9_]*$/i.test(id)) {
    return { ok: false, message: 'Recipe id should use letters, numbers, and underscores.' };
  }
  return { ok: true };
}

export type IntentRecipePhaseEditState = {
  hintsLines: string;
  engineSteps: IntentRecipeEngineStep[];
};

export function phaseToEditState(phase: IntentRecipePhaseValue | undefined): IntentRecipePhaseEditState {
  const normalized = normalizePhaseValue(phase);
  if (!normalized) {
    return { hintsLines: '', engineSteps: [] };
  }
  if (Array.isArray(normalized)) {
    return { hintsLines: normalized.join('\n'), engineSteps: [] };
  }
  return {
    hintsLines: (normalized.hints ?? []).join('\n'),
    engineSteps: (normalized.engineSteps ?? []).map((s) => ({
      ...(String(s.as ?? '').trim() ? { as: String(s.as).trim() } : {}),
      tool: s.tool,
      args: s.args ? { ...s.args } : undefined
    }))
  };
}

export function editStateToPhase(state: IntentRecipePhaseEditState): IntentRecipePhaseValue | undefined {
  const hints = state.hintsLines
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean);
  const steps = state.engineSteps.filter((s) => String(s.tool ?? '').trim());
  if (steps.length === 0 && hints.length === 0) return undefined;
  if (steps.length === 0) {
    return hints;
  }
  return {
    hints,
    engineSteps: steps.map((s) => ({
      ...(String(s.as ?? '').trim() ? { as: String(s.as).trim() } : {}),
      tool: s.tool.trim(),
      ...(s.args && Object.keys(s.args).length > 0 ? { args: { ...s.args } } : {})
    }))
  };
}

export function recipeFromPhaseEdits(
  base: IntentRecipe,
  phaseEdits: Record<IntentRecipePhaseKey, IntentRecipePhaseEditState>
): IntentRecipe {
  const phases: NonNullable<IntentRecipe['phases']> = {};
  for (const key of INTENT_RECIPE_PHASE_KEYS) {
    const phase = editStateToPhase(phaseEdits[key]);
    if (phase != null) phases[key] = phase;
  }
  return { ...base, phases };
}

export function recipeToPhaseEdits(recipe: IntentRecipe): Record<IntentRecipePhaseKey, IntentRecipePhaseEditState> {
  const phases = recipe.phases ?? {};
  return {
    context: phaseToEditState(phases.context),
    action: phaseToEditState(phases.action),
    confirmation: phaseToEditState(phases.confirmation)
  };
}

export function intentRecipesFileFromMergedRecipes(
  recipes: IntentRecipe[],
  version = 1,
  recipeOrder?: string[]
): IntentRecipesFile {
  const file: IntentRecipesFile = { version, recipes: recipes.map((r) => cloneRecipe(r)) };
  if (recipeOrder?.length) {
    file.recipeOrder = [...recipeOrder];
  }
  return file;
}

export function downloadTextFile(filename: string, content: string, mime = 'application/json'): void {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function copyTextToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export function hintLinesToArray(hintsLines: string): string[] {
  return hintsLines
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean);
}

export function hintArrayToLines(hints: string[]): string {
  return hints.join('\n');
}

function collectHintTemplatesFromBundled(): Record<IntentRecipePhaseKey, string[]> {
  const out: Record<IntentRecipePhaseKey, string[]> = {
    context: [],
    action: [],
    confirmation: []
  };
  const seen: Record<IntentRecipePhaseKey, Set<string>> = {
    context: new Set(),
    action: new Set(),
    confirmation: new Set()
  };
  for (const recipe of bundledIntentRecipesCatalog().recipes) {
    for (const key of INTENT_RECIPE_PHASE_KEYS) {
      for (const hint of phaseHints(recipe, key)) {
        if (!seen[key].has(hint)) {
          seen[key].add(hint);
          out[key].push(hint);
        }
      }
    }
  }
  return out;
}

/** Suggested full hint lines per phase (from bundled recipes). */
export const INTENT_RECIPE_HINT_TEMPLATES = collectHintTemplatesFromBundled();

/** Ordered tool names as they appear in hint text (longest match first). */
export function extractWireToolsFromHintText(text: string): string[] {
  const order: string[] = [];
  const sorted = [...INTENT_RECIPE_WIRE_TOOL_OPTIONS].sort((a, b) => b.length - a.length);
  const re = new RegExp(sorted.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'gi');
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    const match = sorted.find((t) => t.toLowerCase() === m![0].toLowerCase());
    if (match && !order.includes(match)) order.push(match);
  }
  return order;
}

function formatToolOrList(tools: string[]): string {
  if (tools.length === 0) return '';
  if (tools.length === 1) return tools[0];
  if (tools.length === 2) return `${tools[0]} or ${tools[1]}`;
  return `${tools.slice(0, -1).join(', ')}, or ${tools[tools.length - 1]}`;
}

export function buildActionFlowHint(tools: string[], middleStep: string, suffix: string): string {
  const tail = suffix.trim();
  if (tools.length === 0) return tail;

  const mid = (middleStep || 'revise XML').trim();

  if (tools.length >= 3) {
    const head = tools.slice(0, -1);
    const last = tools[tools.length - 1];
    let line = `Use ${formatToolOrList(head)} → ${mid} → ${last}`;
    if (tail) line += `; ${tail}`;
    return line;
  }

  let line = `Use ${formatToolOrList(tools)} → ${mid}`;
  if (tail) line += `; ${tail}`;
  return line;
}

/** Best-effort parse of the common “Use tool → step; suffix” action hint shape. */
export function parseActionFlowHint(hint: string): {
  tools: string[];
  middleStep: string;
  suffix: string;
} {
  const text = hint.trim();
  const triple = text.match(/^Use\s+(.+?)\s*→\s*([^→;]+)\s*→\s*([^;]+)(?:;\s*(.*))?$/i);
  if (triple) {
    const headTools = extractWireToolsFromHintText(triple[1]);
    const lastTool = extractWireToolsFromHintText(triple[3]);
    const last = lastTool[lastTool.length - 1];
    const tools = last ? [...headTools.filter((t) => t !== last), last] : headTools;
    return {
      tools: tools.length ? tools : [...headTools, ...lastTool],
      middleStep: triple[2].trim(),
      suffix: (triple[4] ?? '').trim()
    };
  }
  const arrow = text.match(/^Use\s+(.+?)\s*→\s*([^;]+)(?:;\s*(.*))?$/i);
  if (!arrow) {
    return { tools: extractWireToolsFromHintText(text), middleStep: 'revise XML', suffix: '' };
  }
  return {
    tools: extractWireToolsFromHintText(arrow[1]),
    middleStep: arrow[2].trim(),
    suffix: (arrow[3] ?? '').trim()
  };
}
