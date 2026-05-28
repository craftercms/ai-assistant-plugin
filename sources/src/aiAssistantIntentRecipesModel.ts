/**
 * Built-in intent recipe catalog (bundled with the plugin). Studio sites do not need
 * {@code /config/studio/scripts/aiassistant/config/intent-recipes.json} until authors save custom recipes;
 * the configuration UI merges this in-memory catalog with an optional site file.
 */
import bundledCatalog from '../../authoring/scripts/classes/plugins/org/craftercms/aiassistant/studio/engine/routing/authoring-intent-recipes-default.json';
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
  /** Confirmation {@code llmRefine} profile (JSON has {@code llmRefine} without {@code tool}). */
  llmRefine?: string;
  refineHints?: string[];
  userPreamble?: string;
  systemPrompt?: string;
  outputFormat?: 'json' | 'markdown';
  outputKeys?: string[];
  markdownSection?: string;
  passthroughFromSource?: Record<string, string | string[]>;
  passthroughFallbackHints?: Record<string, string[]>;
  passthroughFallbackMaxOutTokens?: Record<string, number>;
};

/** Server JSON shape for confirmation {@code llmRefine} engine steps. */
export type IntentRecipeLlmRefineStep = {
  llmRefine: string;
  /** Named binding for {@code $name.key} in later confirmation tool args. */
  as?: string;
  /** {@code json} returns {@code outputKeys} payload; default {@code markdown} rewrites prose. */
  outputFormat?: 'json' | 'markdown';
  outputKeys?: string[];
  hints?: string[];
  userPreamble?: string;
  systemPrompt?: string;
  markdownSection?: string;
  /** Payload key → {@code ##} heading(s) copied from the assistant turn without LLM rewrite. */
  passthroughFromSource?: Record<string, string | string[]>;
  passthroughFallbackHints?: Record<string, string[]>;
  passthroughFallbackMaxOutTokens?: Record<string, number>;
};

export type IntentRecipePhaseBlock = {
  hints?: string[];
  engineSteps?: Array<IntentRecipeEngineStep | IntentRecipeLlmRefineStep>;
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

/** Confirmation-phase server steps (not prefetch); used by recipe preview swimlane only. */
export const INTENT_RECIPE_CONFIRMATION_STEP_TOOLS = ['llmRefine', 'SlackPostMessage'] as const;

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
        .map((step) => parseEngineStepFromJson(step))
        .filter((x): x is IntentRecipeEngineStep | IntentRecipeLlmRefineStep => x != null);
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

function parsePassthroughFromSource(
  raw: unknown
): Record<string, string | string[]> | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const out: Record<string, string | string[]> = {};
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    const key = String(k ?? '').trim();
    if (!key) continue;
    if (Array.isArray(v)) {
      const headings = v.map((x) => String(x ?? '').trim()).filter(Boolean);
      if (headings.length) out[key] = headings;
    } else {
      const heading = String(v ?? '').trim();
      if (heading) out[key] = heading;
    }
  }
  return Object.keys(out).length ? out : undefined;
}

function parseStringListMap(raw: unknown): Record<string, string[]> | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const out: Record<string, string[]> = {};
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    const key = String(k ?? '').trim();
    if (!key || !Array.isArray(v)) continue;
    const lines = v.map((x) => String(x ?? '').trim()).filter(Boolean);
    if (lines.length) out[key] = lines;
  }
  return Object.keys(out).length ? out : undefined;
}

function parseNumberMap(raw: unknown): Record<string, number> | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    const key = String(k ?? '').trim();
    if (!key || v == null) continue;
    const n = typeof v === 'number' ? v : Number(String(v).trim());
    if (Number.isFinite(n) && n > 0) out[key] = n;
  }
  return Object.keys(out).length ? out : undefined;
}

function parseEngineStepFromJson(step: unknown): IntentRecipeEngineStep | IntentRecipeLlmRefineStep | null {
  if (!step || typeof step !== 'object') return null;
  const s = step as Record<string, unknown>;
  const llmRefine = String(s.llmRefine ?? '').trim();
  if (llmRefine) {
    const refineHints = Array.isArray(s.hints)
      ? s.hints.map((x) => String(x ?? '')).filter(Boolean)
      : undefined;
    const userPreamble = String(s.userPreamble ?? '').trim();
    const systemPrompt = String(s.systemPrompt ?? '').trim();
    const asName = String(s.as ?? '').trim();
    const outputFormat =
      s.outputFormat === 'json' || s.outputFormat === 'markdown' ? s.outputFormat : undefined;
    const outputKeys = Array.isArray(s.outputKeys)
      ? s.outputKeys.map((x) => String(x ?? '')).filter(Boolean)
      : undefined;
    const markdownSection = String(s.markdownSection ?? '').trim();
    const passthroughFromSource = parsePassthroughFromSource(s.passthroughFromSource);
    const passthroughFallbackHints = parseStringListMap(s.passthroughFallbackHints);
    const passthroughFallbackMaxOutTokens = parseNumberMap(s.passthroughFallbackMaxOutTokens);
    return {
      llmRefine,
      ...(asName ? { as: asName } : {}),
      ...(outputFormat ? { outputFormat } : {}),
      ...(outputKeys?.length ? { outputKeys } : {}),
      ...(refineHints?.length ? { hints: refineHints } : {}),
      ...(userPreamble ? { userPreamble } : {}),
      ...(systemPrompt ? { systemPrompt } : {}),
      ...(markdownSection ? { markdownSection } : {}),
      ...(passthroughFromSource ? { passthroughFromSource } : {}),
      ...(passthroughFallbackHints ? { passthroughFallbackHints } : {}),
      ...(passthroughFallbackMaxOutTokens ? { passthroughFallbackMaxOutTokens } : {})
    } satisfies IntentRecipeLlmRefineStep;
  }
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
}

/** UI/editor normalized step (includes synthetic {@code tool: llmRefine}). */
export function normalizeEngineStepForUi(
  step: IntentRecipeEngineStep | IntentRecipeLlmRefineStep
): IntentRecipeEngineStep {
  const withTool = step as IntentRecipeEngineStep;
  if (withTool.tool?.trim()) {
    return withTool;
  }
  const r = step as IntentRecipeLlmRefineStep;
  return {
    tool: 'llmRefine',
    llmRefine: r.llmRefine,
    ...(r.as?.trim() ? { as: r.as.trim() } : {}),
    ...(r.outputFormat ? { outputFormat: r.outputFormat } : {}),
    ...(r.outputKeys?.length ? { outputKeys: [...r.outputKeys] } : {}),
    ...(r.hints?.length ? { refineHints: [...r.hints] } : {}),
    ...(r.userPreamble ? { userPreamble: r.userPreamble } : {}),
    ...(r.systemPrompt ? { systemPrompt: r.systemPrompt } : {}),
    ...(r.markdownSection ? { markdownSection: r.markdownSection } : {}),
    ...(r.passthroughFromSource ? { passthroughFromSource: { ...r.passthroughFromSource } } : {}),
    ...(r.passthroughFallbackHints
      ? { passthroughFallbackHints: { ...r.passthroughFallbackHints } }
      : {}),
    ...(r.passthroughFallbackMaxOutTokens
      ? { passthroughFallbackMaxOutTokens: { ...r.passthroughFallbackMaxOutTokens } }
      : {})
  };
}

export function engineStepToJson(step: IntentRecipeEngineStep): IntentRecipeEngineStep | IntentRecipeLlmRefineStep {
  const llm = String(step.llmRefine ?? '').trim();
  if (step.tool === 'llmRefine' || llm) {
    const out: IntentRecipeLlmRefineStep = { llmRefine: llm || 'default' };
    if (step.as?.trim()) out.as = step.as.trim();
    if (step.outputFormat) out.outputFormat = step.outputFormat;
    if (step.outputKeys?.length) out.outputKeys = [...step.outputKeys];
    if (step.refineHints?.length) out.hints = [...step.refineHints];
    if (step.userPreamble?.trim()) out.userPreamble = step.userPreamble.trim();
    if (step.systemPrompt?.trim()) out.systemPrompt = step.systemPrompt.trim();
    if (step.markdownSection?.trim()) out.markdownSection = step.markdownSection.trim();
    if (step.passthroughFromSource && Object.keys(step.passthroughFromSource).length > 0) {
      out.passthroughFromSource = { ...step.passthroughFromSource };
    }
    if (step.passthroughFallbackHints && Object.keys(step.passthroughFallbackHints).length > 0) {
      out.passthroughFallbackHints = Object.fromEntries(
        Object.entries(step.passthroughFallbackHints).map(([k, v]) => [k, [...v]])
      );
    }
    if (
      step.passthroughFallbackMaxOutTokens &&
      Object.keys(step.passthroughFallbackMaxOutTokens).length > 0
    ) {
      out.passthroughFallbackMaxOutTokens = { ...step.passthroughFallbackMaxOutTokens };
    }
    return out;
  }
  const out: IntentRecipeEngineStep = { tool: step.tool.trim() };
  if (step.as?.trim()) out.as = step.as.trim();
  if (step.args && Object.keys(step.args).length > 0) out.args = { ...step.args };
  return out;
}

export function collectEngineStepsFromRecipe(recipe: IntentRecipe): IntentRecipeEngineStep[] {
  const out: IntentRecipeEngineStep[] = [];
  const phases = recipe.phases;
  if (!phases) return out;
  for (const key of INTENT_RECIPE_PHASE_KEYS) {
    const phase = normalizePhaseValue(phases[key]);
    if (phase && !Array.isArray(phase) && phase.engineSteps?.length) {
      for (const step of phase.engineSteps) {
        out.push(normalizeEngineStepForUi(step));
      }
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
  return (phase.engineSteps ?? []).map((s) => normalizeEngineStepForUi(s));
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
    engineSteps: (normalized.engineSteps ?? []).map((s) => {
      const ui = normalizeEngineStepForUi(s);
      return {
        ...(String(ui.as ?? '').trim() ? { as: String(ui.as).trim() } : {}),
        tool: ui.tool,
        args: ui.args ? { ...ui.args } : undefined,
        ...(ui.llmRefine ? { llmRefine: ui.llmRefine } : {}),
        ...(ui.refineHints?.length ? { refineHints: [...ui.refineHints] } : {}),
        ...(ui.userPreamble ? { userPreamble: ui.userPreamble } : {}),
        ...(ui.systemPrompt ? { systemPrompt: ui.systemPrompt } : {}),
        ...(ui.outputFormat ? { outputFormat: ui.outputFormat } : {}),
        ...(ui.outputKeys?.length ? { outputKeys: [...ui.outputKeys] } : {}),
        ...(ui.markdownSection ? { markdownSection: ui.markdownSection } : {})
      };
    })
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
    engineSteps: steps.map((s) => engineStepToJson(s))
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
