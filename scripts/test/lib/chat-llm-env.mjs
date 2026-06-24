/** Shared CHAT_LLM / CHAT_LLM_MODEL handling for functional chat runners. */

export function sleepMs(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return Promise.resolve();
  return new Promise((resolve) => setTimeout(resolve, n));
}

export function isRateLimitReason(reason) {
  const r = String(reason || '');
  return (
    r.includes('429') ||
    r.includes('TOO_MANY_REQUESTS') ||
    r.includes('rate_limit_error') ||
    r.includes('rate limit')
  );
}

export function resolveInterTurnDelayMs(llmOverride) {
  const explicit = process.env.CHAT_INTER_TURN_DELAY_MS;
  if (explicit != null && String(explicit).trim() !== '') {
    const n = Number(explicit);
    if (Number.isFinite(n) && n >= 0) return n;
  }
  if (/^claude$/i.test(String(llmOverride || '').trim())) return 45000;
  return 0;
}

export function resolveRateLimitRetries(llmOverride) {
  const explicit = process.env.CHAT_RATE_LIMIT_RETRIES;
  if (explicit != null && String(explicit).trim() !== '') {
    const n = Number(explicit);
    if (Number.isFinite(n) && n >= 0) return Math.floor(n);
  }
  return /^claude$/i.test(String(llmOverride || '').trim()) ? 2 : 0;
}

export function resolveRateLimitBackoffMs() {
  const n = Number(process.env.CHAT_RATE_LIMIT_BACKOFF_MS || '65000');
  return Number.isFinite(n) && n > 0 ? n : 65000;
}

/** Merge scenario defaults with CHAT_LLM / CHAT_LLM_MODEL env overrides. */
export function applyChatLlmEnvToDefaults(defaults) {
  const llmOverride = String(process.env.CHAT_LLM || '').trim();
  const llmModelOverride = String(process.env.CHAT_LLM_MODEL || '').trim();
  if (llmOverride) {
    defaults.llm = llmOverride;
    if (!llmModelOverride) {
      delete defaults.llmModel;
    }
  }
  if (llmModelOverride) {
    defaults.llmModel = llmModelOverride;
  }
  return { llmOverride, llmModelOverride };
}

/** Base POST body fields for ai/stream (concurrent runners, etc.). */
export function chatStreamBodyBase({ agentId, siteId, extra = {} }) {
  const body = { agentId, siteId, ...extra };
  const llm = String(process.env.CHAT_LLM || '').trim();
  const llmModel = String(process.env.CHAT_LLM_MODEL || '').trim();
  if (llm) {
    body.llm = llm;
    if (llmModel) body.llmModel = llmModel;
  } else {
    body.llm = body.llm ?? 'openAI';
    body.llmModel = body.llmModel ?? 'gpt-4o-mini';
  }
  return body;
}
