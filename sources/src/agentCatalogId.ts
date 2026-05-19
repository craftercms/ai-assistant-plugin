/** Stable agent id from a catalog row in `config/studio/ai-assistant/agents.json`. */
export function readAgentCatalogId(entry: Record<string, unknown> | null | undefined): string {
  if (!entry) return '';
  return String(entry.agentId ?? '').trim();
}

/** New opaque id for a chat agent catalog row. */
export function newAgentCatalogId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `agent-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;
}

/** Ensures `agentId` is set (generates when missing) and drops duplicate `id` alias. */
export function ensureAgentCatalogId(entry: Record<string, unknown>): Record<string, unknown> {
  const existing = readAgentCatalogId(entry);
  return withAgentCatalogId(entry, existing || newAgentCatalogId());
}

/** Persist `agentId` on a catalog row (drops duplicate `id` alias). */
export function withAgentCatalogId(entry: Record<string, unknown>, agentId: string): Record<string, unknown> {
  const trimmed = agentId.trim();
  const out = { ...entry, agentId: trimmed };
  delete out.id;
  return out;
}
