/** Stable agent id from a catalog row in `config/studio/ai-assistant/agents.json`. */
export function readAgentCatalogId(entry: Record<string, unknown> | null | undefined): string {
  if (!entry) return '';
  return String(entry.agentId ?? entry.id ?? '').trim();
}

/** Persist `agentId` and `id` on a catalog row. */
export function withAgentCatalogId(entry: Record<string, unknown>, id: string): Record<string, unknown> {
  const trimmed = id.trim();
  return {
    ...entry,
    agentId: trimmed,
    id: trimmed
  };
}
