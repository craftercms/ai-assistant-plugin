/**
 * Chat agent row shape for `config/studio/ai-assistant/agents.json` (Project Tools → Agents).
 */
/**
 * Studio stream `llm` value. Use a provider id accepted by server {@code StudioAiLlmKind.normalize}
 * (e.g. `openAI`, `claude`, `xAI`, `deepSeek`, `llama`, `gemini`, or `script:<id>`).
 */
export type AgentLlm = string;

/** Optional per-agent markdown RAG source (OpenAI path); configured in `agents.json` as `expertSkills`. */
export interface ExpertSkillConfig {
  /** Display name for the system prompt table. */
  name?: string;
  /** Public http(s) URL whose body is treated as UTF-8 markdown for chunking + embeddings. */
  url: string;
  /** When to call QueryExpertGuidance for this skill. */
  description?: string;
}

export interface AgentConfig {
  /**
   * Stable agent id for stream `agentId` and merge/dedupe; from **`agentId`** or **`id`** in `agents.json`.
   * With **label**, forms the composite {@link agentStableKey}. May be empty when omitted in config.
   */
  id: string;
  label: string;
  icon?: string;
  /**
   * `true` = floating dialog. `false` or omitted = Experience Builder right (ICE) tools panel
   * (edit mode on). Default is panel. XML / JSON: `<openAsPopup>true</openAsPopup>` or `"openAsPopup": true`.
   */
  openAsPopup?: boolean;
  /** Provider id (e.g. `openAI`, `claude`, `script:…`). Omitted on the client when unset; server merges from `agents.json` when possible. */
  llm?: AgentLlm;
  /**
   * When false (`enableTools: false` in `agents.json`), the plugin sends `enableTools: false` so OpenAI
   * requests omit CMS function tools. Omitted or true: default (tools on for OpenAI).
   */
  enableTools?: boolean;
  /**
   * Optional subset of built-in CMS tool wire names (e.g. `GetContent`, `WriteContent`). Forwarded on stream POST as
   * `enabledBuiltInTools` when non-empty. Include `mcp:*` to allow all MCP tools. Omitted = full catalog (subject to site `tools.json`).
   */
  enabledBuiltInTools?: string[];
  /** Optional provider model id when `llm` is `openAI` (e.g. `gpt-4o-mini`). */
  llmModel?: string;
  /** OpenAI Images API model when llm is openAI (e.g. gpt-image-1) — no JVM fallback. */
  imageModel?: string;
  /**
   * GenerateImage backend. Blank = built-in GenerateImage HTTP wire when configured; values **none**, **off**, or **disabled** turn the tool off; **script:{id}** runs `/scripts/aiassistant/imagegen/{id}/generate.groovy`.
   */
  imageGenerator?: string;
  /**
   * Optional OpenAI API key from agent config — **not recommended** (exposed in Studio config / sent on requests).
   * Used only when `OPENAI_API_KEY` / JVM keys are unset. For local testing.
   */
  llmApiKey?: string;
  /** Key in site {@code secrets.json} for this agent's LLM credentials (e.g. {@code openai_api_key}). Omitted for script LLMs. */
  llmSecretKey?: string;
  prompts?: PromptConfig[];
  /** Markdown URLs for server-side QueryExpertGuidance (Spring AI vector store); OpenAI agents only. */
  expertSkills?: ExpertSkillConfig[];
  /**
   * Parallel **TranslateContentBatch** workers when the model omits **maxConcurrency** (1–64).
   * `agents.json`: **`translateBatchConcurrency`** (or `translate_batch_concurrency`). Omitted → server default **25**.
   */
  translateBatchConcurrency?: number;
}

/**
 * Stable key for matching agents between `agents.json`, form field properties, and the form-control UI.
 * When both `id` and `label` are set, uses a composite key so multiple `<agent>` rows with the **same**
 * backend `id` (e.g. same UUID, different labels) stay distinct — otherwise merging collapses them.
 */
export function agentStableKey(a: Pick<AgentConfig, 'id' | 'label'>): string {
  const id = (a.id || '').trim();
  const label = (a.label || '').trim();
  if (id && label) return `${id}\u001e${label}`;
  if (id) return id;
  return label || 'agent';
}

/**
 * Form engine control property `name` for “show this agent in the panel”.
 * Must stay in sync with `cqAgentPropName` in `sources/control/ai-assistant/main.js`.
 */
export function agentFormPropertyName(a: Pick<AgentConfig, 'id' | 'label'>): string {
  const key = agentStableKey(a);
  const s = key.replace(/[^a-zA-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return 'cqShow_' + s;
}

/**
 * Sentinel label when a catalog row omits **{@code label}**.
 */
export const AI_ASSISTANT_AGENT_LABEL_FALLBACK = 'AI Assistant';

/**
 * Default catalog **{@code agentId}** when none is configured (empty — authors should set id in `agents.json`).
 */
export const AI_ASSISTANT_DEFAULT_AGENT_ID = '';

/** Keep first occurrence per {@link agentStableKey} (order preserved). */
export function dedupeAgentsByStableKey(agents: AgentConfig[]): AgentConfig[] {
  const m = new Map<string, AgentConfig>();
  for (const a of agents) {
    const k = agentStableKey(a);
    if (!m.has(k)) m.set(k, a);
  }
  return Array.from(m.values());
}

/**
 * Remove placeholder rows (label exactly {@link AI_ASSISTANT_AGENT_LABEL_FALLBACK}) when another agent has a real label.
 */
export function dropPlaceholderAgentsWhenRicherMatchesExist(agents: AgentConfig[]): AgentConfig[] {
  const deduped = dedupeAgentsByStableKey(agents);
  if (deduped.length <= 1) return deduped;

  const hasRicher = deduped.some((a) => {
    const lab = (a.label || '').trim();
    return lab && lab !== AI_ASSISTANT_AGENT_LABEL_FALLBACK;
  });
  if (!hasRicher) return deduped;

  return deduped.filter((a) => (a.label || '').trim() !== AI_ASSISTANT_AGENT_LABEL_FALLBACK);
}

export type PromptConfig = {
  userText: string;
  additionalContext?: string;
  /** When true, chip-triggered request sends {@code omitTools} — OpenAI omits CMS tools for that LLM call (copy/generation focus). */
  omitTools?: boolean;
};

const DEFAULT_AGENT_ID = '';

/** Fallback when no config or parsing fails — one toolbar/menu row so click always has a target. */
const DEFAULT_AGENT: AgentConfig = {
  id: AI_ASSISTANT_DEFAULT_AGENT_ID,
  label: 'Studio AI Assistant',
  llm: 'openAI',
  llmModel: 'gpt-4o-mini',
  prompts: []
};

/** Fallback list so Helper click / agent menus always have at least one entry while the catalog loads. */
export const DEFAULT_AGENTS: AgentConfig[] = [DEFAULT_AGENT];

/**
 * Default agents for the Form Engine AI Assistant control when `agents.json` is missing (see `main.js` fallback).
 * Keep default **{@code id}** in sync with `sources/control/ai-assistant/main.js` (`AIASSISTANT_FALLBACK_AGENTS`).
 */
export const DEFAULT_FORM_CONTROL_AGENTS: AgentConfig[] = [
  {
    id: AI_ASSISTANT_DEFAULT_AGENT_ID,
    label: 'Content assistant',
    llm: 'openAI',
    llmModel: 'gpt-4o-mini'
  }
];

export function normalizeExpertSkillsRaw(raw: unknown): ExpertSkillConfig[] | undefined {
  if (raw == null) return undefined;
  const rows: ExpertSkillConfig[] = [];
  const pushFromRecord = (r: Record<string, unknown>) => {
    const url = extractString(r.url) ?? extractString(r.href);
    if (!url?.trim()) return;
    rows.push({
      name: extractString(r.name) ?? 'Expert guidance',
      url: url.trim(),
      description: extractString(r.description) ?? ''
    });
  };
  if (Array.isArray(raw)) {
    for (const item of raw) {
      if (!item || typeof item !== 'object') continue;
      pushFromRecord(item as Record<string, unknown>);
    }
  } else if (typeof raw === 'object') {
    const o = raw as Record<string, unknown>;
    const nested = o.expertSkill;
    if (Array.isArray(nested)) {
      for (const item of nested) {
        if (!item || typeof item !== 'object') continue;
        pushFromRecord(item as Record<string, unknown>);
      }
    } else if (nested && typeof nested === 'object') {
      pushFromRecord(nested as Record<string, unknown>);
    }
  }
  return rows.length ? rows : undefined;
}

export function normalizeEnabledBuiltInToolsRaw(raw: unknown): string[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  const out: string[] = [];
  for (const x of raw) {
    const s = String(x ?? '').trim();
    if (s) out.push(s);
  }
  return out.length ? out : undefined;
}

/** Integer in inclusive range; undefined if missing or invalid. */
export function extractPositiveInt(
  o: Record<string, unknown>,
  min: number,
  max: number,
  ...keys: string[]
): number | undefined {
  for (const k of keys) {
    const v = o[k];
    if (v == null) continue;
    let n: number;
    if (typeof v === 'number' && Number.isFinite(v)) n = Math.floor(v);
    else {
      const s = extractString(v);
      if (!s) continue;
      n = parseInt(s, 10);
    }
    if (!Number.isFinite(n)) continue;
    if (n < min || n > max) continue;
    return n;
  }
  return undefined;
}

function extractBooleanFromRecord(o: Record<string, unknown>, ...keys: string[]): boolean | undefined {
  for (const k of keys) {
    const v = o[k];
    if (v === true) return true;
    if (v === false) return false;
    const s = extractString(v)?.toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no') return false;
  }
  return undefined;
}

/** First matching optional boolean on a Studio widget/configuration object (JSON props). */
export function readOptionalBooleanFromConfiguration(o: unknown, ...keys: string[]): boolean | undefined {
  if (o == null || typeof o !== 'object') return undefined;
  return extractBooleanFromRecord(o as Record<string, unknown>, ...keys);
}

function extractString(v: unknown): string | undefined {
  if (v == null) return undefined;
  if (typeof v === 'string') return v.trim() || undefined;
  if (Array.isArray(v)) {
    const first = v[0];
    if (first != null) return extractString(first);
    return undefined;
  }
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>;
    const candidates = [
      o.$text,
      o.value,
      o['#text'],
      o.__text,
      o._,
      o['@_id'],
      o.text,
      o.content
    ];
    for (const c of candidates) {
      if (typeof c === 'string') return c.trim() || undefined;
    }
    for (const c of candidates) {
      if (c != null && typeof c === 'object') {
        const s = extractString(c);
        if (s) return s;
      }
    }
    const values = Object.values(o);
    if (values.length > 0) {
      const first = extractString(values[0]);
      if (first) return first;
    }
  }
  return undefined;
}
