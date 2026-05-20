/** Studio module path for site secrets registry (under {@code config/studio/}). */
export const SECRETS_JSON_REL = 'scripts/aiassistant/config/secrets.json';

export type AiAssistantSecretValueKind = 'env' | 'enc' | 'literal' | 'secret_ref' | 'empty';

export type AiAssistantSecretAdminRow = {
  key: string;
  label: string;
  configured: boolean;
  valueKind: AiAssistantSecretValueKind;
  /** Normalized LLM id when this row is a built-in provider slot (e.g. openAI). */
  llmProvider?: string;
  optional?: boolean;
  defaultEnvVar?: string;
  suggestedExpression?: string;
  valueExpression?: string;
  envVar?: string;
  hasEncryptedLiteral?: boolean;
};

export type AiAssistantSecretsIndexResponse = {
  ok?: boolean;
  message?: string;
  /** Non-fatal: server catalog missing or partial; UI still shows built-in provider rows. */
  catalogWarning?: string;
  /** Server created default secrets.json on this load. */
  secretsSeeded?: boolean;
  siteId?: string;
  studioPath?: string;
  knownSecrets?: AiAssistantSecretAdminRow[];
  customSecrets?: AiAssistantSecretAdminRow[];
};

/**
 * Built-in LLM provider secret slots (mirrors {@code StudioAiAssistantSecretsCatalog} on the server).
 * The UI always lists these so authors see every provider even before {@code secrets.json} exists.
 */
export const AI_ASSISTANT_KNOWN_SECRET_SLOTS: ReadonlyArray<{
  key: string;
  label: string;
  llmProvider?: string;
  defaultEnvVar: string;
  optional?: boolean;
}> = [
  { key: 'openai_api_key', label: 'OpenAI', llmProvider: 'openAI', defaultEnvVar: 'OPENAI_API_KEY' },
  { key: 'anthropic_api_key', label: 'Claude (Anthropic)', llmProvider: 'claude', defaultEnvVar: 'ANTHROPIC_API_KEY' },
  { key: 'xai_api_key', label: 'xAI', llmProvider: 'xAI', defaultEnvVar: 'XAI_API_KEY' },
  { key: 'deepseek_api_key', label: 'DeepSeek', llmProvider: 'deepSeek', defaultEnvVar: 'DEEPSEEK_API_KEY' },
  { key: 'llama_api_key', label: 'Llama (Ollama-compatible)', llmProvider: 'llama', defaultEnvVar: 'LLAMA_API_KEY' },
  { key: 'gemini_api_key', label: 'Gemini (Google)', llmProvider: 'gemini', defaultEnvVar: 'GEMINI_API_KEY' }
];

/** Integration keys (mirrors optional slots in {@code StudioAiAssistantSecretsCatalog}). */
export const AI_ASSISTANT_INTEGRATION_SECRET_SLOTS: ReadonlyArray<{
  key: string;
  label: string;
  defaultEnvVar: string;
  optional?: boolean;
}> = [{ key: 'serpapi_api_key', label: 'SerpAPI (web search)', defaultEnvVar: 'SERPAPI_API_KEY', optional: true }];

function secretAdminRowFromSlot(slot: {
  key: string;
  label: string;
  defaultEnvVar: string;
  llmProvider?: string;
  optional?: boolean;
}): AiAssistantSecretAdminRow {
  const expr = `\${env:${slot.defaultEnvVar}}`;
  return {
    key: slot.key,
    label: slot.label,
    configured: false,
    valueKind: 'env',
    llmProvider: slot.llmProvider,
    optional: slot.optional,
    defaultEnvVar: slot.defaultEnvVar,
    suggestedExpression: expr,
    valueExpression: expr,
    envVar: slot.defaultEnvVar
  };
}

export function defaultKnownSecretAdminRows(): AiAssistantSecretAdminRow[] {
  return [...AI_ASSISTANT_KNOWN_SECRET_SLOTS, ...AI_ASSISTANT_INTEGRATION_SECRET_SLOTS].map(secretAdminRowFromSlot);
}

/** Merge server admin rows over the built-in catalog (server wins per key). */
/** Built-in {@code secrets.json} key for a Project Tools LLM vendor id (empty for script). */
export function secretKeyForLlmVendor(vendor: string): string {
  const v = (vendor ?? '').trim();
  switch (v) {
    case 'openAI':
      return 'openai_api_key';
    case 'claude':
      return 'anthropic_api_key';
    case 'xAI':
      return 'xai_api_key';
    case 'deepSeek':
      return 'deepseek_api_key';
    case 'llama':
      return 'llama_api_key';
    case 'gemini':
      return 'gemini_api_key';
    default:
      return '';
  }
}

export function secretKeysFromSecretsIndex(
  known?: AiAssistantSecretAdminRow[] | null,
  custom?: AiAssistantSecretAdminRow[] | null
): string[] {
  const keys = new Set<string>();
  for (const r of known ?? []) {
    const k = r.key?.trim();
    if (k) keys.add(k);
  }
  for (const r of custom ?? []) {
    const k = r.key?.trim();
    if (k) keys.add(k);
  }
  return [...keys].sort();
}

const BUILTIN_PROVIDER_SECRET_KEY_SET = new Set(AI_ASSISTANT_KNOWN_SECRET_SLOTS.map((s) => s.key));

/** True when {@code key} is a fixed LLM provider slot (not a project custom secret). */
export function isBuiltinProviderSecretKey(key: string): boolean {
  return BUILTIN_PROVIDER_SECRET_KEY_SET.has((key ?? '').trim());
}

/** Custom secret keys only (MCP auth and cross-provider overrides). */
export function customSecretKeysFromSecretsIndex(custom?: AiAssistantSecretAdminRow[] | null): string[] {
  const keys = new Set<string>();
  for (const r of custom ?? []) {
    const k = r.key?.trim();
    if (k) keys.add(k);
  }
  return [...keys].sort();
}

export function builtinSecretSlotLabel(key: string): string | undefined {
  return AI_ASSISTANT_KNOWN_SECRET_SLOTS.find((s) => s.key === key.trim())?.label;
}

/** Parse {@code Authorization} (or similar) header values that reference {@code secrets.json}. */
export function secretKeyFromSecretRefHeaderValue(value: string): string {
  const v = (value ?? '').trim();
  const m = v.match(/^(?:Bearer\s+)?\$\{secret:([a-z][a-z0-9_]{0,63})\}$/i);
  return m ? m[1] : '';
}

export function mcpAuthorizationHeaderForSecretKey(secretKey: string): string {
  const k = secretKey.trim();
  return k ? `Bearer \${secret:${k}}` : '';
}

export function mergeKnownSecretsWithServer(
  serverKnown?: AiAssistantSecretAdminRow[] | null
): AiAssistantSecretAdminRow[] {
  const base = defaultKnownSecretAdminRows();
  if (!serverKnown?.length) {
    return base;
  }
  const byKey = new Map(serverKnown.map((r) => [r.key, r]));
  return base.map((b) => {
    const s = byKey.get(b.key);
    return s ? { ...b, ...s, label: s.label || b.label } : b;
  });
}

/** Save payload item — server resolves/encrypts; never returns plaintext literals. */
export type AiAssistantSecretSaveEntry = {
  key: string;
  remove?: boolean;
  clear?: boolean;
  envVar?: string;
  valueExpression?: string;
  plainValue?: string;
  encCipher?: string;
};

export type AiAssistantSecretsMutateResponse = {
  ok?: boolean;
  message?: string;
};

/** UI-only value mode when editing a row. */
export type AiAssistantSecretEditMode = 'env' | 'enc' | 'plain';

export type AiAssistantSecretRowDraft = {
  key: string;
  label: string;
  known: boolean;
  /** Built-in provider row not yet written to secrets.json. */
  notPersisted: boolean;
  llmProvider?: string;
  optional?: boolean;
  editMode: AiAssistantSecretEditMode;
  envVar: string;
  expressionDraft: string;
  plainDraft: string;
  hasStoredLiteral: boolean;
  remove: boolean;
};

export function defaultEnvVarForKey(key: string, row?: AiAssistantSecretAdminRow): string {
  const fromRow = row?.defaultEnvVar?.trim() || row?.envVar?.trim();
  if (fromRow) return fromRow;
  const suggested = row?.suggestedExpression?.trim();
  if (suggested?.startsWith('${env:') && suggested.endsWith('}')) {
    return suggested.slice(6, -1);
  }
  return '';
}

export function rowDraftFromAdmin(row: AiAssistantSecretAdminRow, known: boolean): AiAssistantSecretRowDraft {
  const kind = row.valueKind ?? 'empty';
  const defaultEnv = defaultEnvVarForKey(row.key, row);
  let editMode: AiAssistantSecretEditMode = 'env';
  if (kind === 'enc' || kind === 'secret_ref') {
    editMode = 'enc';
  } else if (kind === 'literal') {
    editMode = 'plain';
  } else if (kind === 'env' || (known && defaultEnv)) {
    editMode = 'env';
  }

  return {
    key: row.key,
    label: row.label || row.key,
    known,
    notPersisted: known && !row.configured,
    llmProvider: row.llmProvider?.trim() || undefined,
    optional: Boolean(row.optional),
    editMode,
    envVar: row.envVar?.trim() || defaultEnv,
    expressionDraft:
      kind === 'enc' || kind === 'secret_ref' ? row.valueExpression?.trim() || '' : row.suggestedExpression?.trim() || '',
    plainDraft: '',
    hasStoredLiteral: Boolean(row.hasEncryptedLiteral),
    remove: false
  };
}

export function buildSecretSaveEntries(drafts: AiAssistantSecretRowDraft[]): AiAssistantSecretSaveEntry[] {
  const out: AiAssistantSecretSaveEntry[] = [];
  for (const d of drafts) {
    const key = d.key.trim();
    if (!key || d.remove) {
      if (key) out.push({ key, remove: true });
      continue;
    }
    if (d.editMode === 'env') {
      const envVar = d.envVar.trim();
      if (!envVar) {
        out.push({ key, clear: true });
      } else {
        out.push({ key, envVar });
      }
      continue;
    }
    if (d.editMode === 'enc') {
      const expr = d.expressionDraft.trim();
      if (!expr) {
        out.push({ key, clear: true });
      } else if (expr.startsWith('${enc:')) {
        out.push({ key, valueExpression: expr });
      } else {
        out.push({ key, encCipher: expr });
      }
      continue;
    }
    const plain = d.plainDraft.trim();
    if (plain) {
      out.push({ key, plainValue: plain });
    }
  }
  return out;
}
