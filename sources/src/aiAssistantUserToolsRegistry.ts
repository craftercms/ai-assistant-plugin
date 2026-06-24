/**
 * Parse / serialize site {@code user-tools/registry.json} for Project Tools UI.
 */

export type UserToolRegistryRow = {
  id: string;
  script: string;
  description: string;
  matchHints: string[];
  dontMatchHints: string[];
  priority: number;
};

export type UserToolRegistryDocument = {
  wrapper: 'tools' | 'array' | 'empty';
  tools: UserToolRegistryRow[];
  /** Preserved top-level keys when wrapper is {@code tools}. */
  extraRoot?: Record<string, unknown>;
};

/** One hint per line for registry editor text fields. */
export function hintsMultiline(hints: string[] | undefined): string {
  return (hints ?? []).map((h) => h.trim()).filter(Boolean).join('\n');
}

/** Parses newline-separated hints from the Project Tools registry editor. */
export function hintsFromMultiline(text: string): string[] {
  const out: string[] = [];
  for (const line of (text ?? '').split(/\r?\n/)) {
    const n = line.trim();
    if (n) {
      out.push(n);
    }
  }
  return out;
}

function hintArray(raw: unknown): string[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const out: string[] = [];
  for (const o of raw) {
    const n = String(o ?? '').trim();
    if (n) {
      out.push(n);
    }
  }
  return out;
}

/** Normalizes one registry JSON object; returns null when id or script is missing. */
function normalizeToolRow(o: unknown): UserToolRegistryRow | null {
  if (!o || typeof o !== 'object') {
    return null;
  }
  const m = o as Record<string, unknown>;
  const id = String(m.id ?? '').trim();
  let script = String(m.script ?? '').trim();
  if (!script) {
    script = String(m.file ?? '').trim();
  }
  if (!id || !script) {
    return null;
  }
  let priority = 0;
  const pr = m.priority;
  if (typeof pr === 'number' && Number.isFinite(pr)) {
    priority = Math.trunc(pr);
  }
  return {
    id,
    script,
    description: String(m.description ?? m.desc ?? '').trim(),
    matchHints: hintArray(m.matchHints),
    dontMatchHints: hintArray(m.dontMatchHints),
    priority
  };
}

/** Parses {@code registry.json} text (array, {@code { tools }}, or empty). */
export function parseRegistryDocument(raw: string): UserToolRegistryDocument {
  const trimmed = (raw ?? '').trim();
  if (!trimmed) {
    return { wrapper: 'empty', tools: [] };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new Error(`Invalid user-tools registry JSON: ${msg}`);
  }
  if (Array.isArray(parsed)) {
    const tools: UserToolRegistryRow[] = [];
    for (const o of parsed) {
      const row = normalizeToolRow(o);
      if (row) {
        tools.push(row);
      }
    }
    return { wrapper: 'array', tools };
  }
  if (parsed && typeof parsed === 'object') {
    const o = parsed as Record<string, unknown>;
    const toolsRaw = o.tools ?? o.entries;
    const tools: UserToolRegistryRow[] = [];
    if (Array.isArray(toolsRaw)) {
      for (const item of toolsRaw) {
        const row = normalizeToolRow(item);
        if (row) {
          tools.push(row);
        }
      }
    }
    const extraRoot = { ...o };
    delete extraRoot.tools;
    delete extraRoot.entries;
    return { wrapper: 'tools', tools, extraRoot };
  }
  return { wrapper: 'empty', tools: [] };
}

function rowToJson(row: UserToolRegistryRow): Record<string, unknown> {
  const out: Record<string, unknown> = {
    id: row.id,
    script: row.script,
    description: row.description
  };
  if (row.matchHints.length) {
    out.matchHints = row.matchHints;
  }
  if (row.dontMatchHints.length) {
    out.dontMatchHints = row.dontMatchHints;
  }
  if (row.priority) {
    out.priority = row.priority;
  }
  return out;
}

/** Writes registry JSON preserving the document wrapper shape ({@code tools} vs array). */
export function serializeRegistryDocument(doc: UserToolRegistryDocument): string {
  const rows = doc.tools.map(rowToJson);
  if (doc.wrapper === 'array') {
    return JSON.stringify(rows, null, 2);
  }
  if (doc.wrapper === 'tools') {
    const root: Record<string, unknown> = { ...(doc.extraRoot ?? {}), tools: rows };
    return JSON.stringify(root, null, 2);
  }
  return JSON.stringify({ tools: rows }, null, 2);
}

/** Patches description and routing hints for an existing tool id in registry JSON. */
export function updateRegistryTool(
  registryRaw: string,
  toolId: string,
  patch: Partial<Omit<UserToolRegistryRow, 'id' | 'script'>>
): string {
  const doc = parseRegistryDocument(registryRaw);
  const idx = doc.tools.findIndex((t) => t.id === toolId);
  if (idx < 0) {
    throw new Error(`Tool "${toolId}" not found in registry.`);
  }
  const cur = doc.tools[idx];
  doc.tools[idx] = {
    ...cur,
    description: patch.description !== undefined ? patch.description : cur.description,
    matchHints: patch.matchHints !== undefined ? patch.matchHints : cur.matchHints,
    dontMatchHints: patch.dontMatchHints !== undefined ? patch.dontMatchHints : cur.dontMatchHints,
    priority: patch.priority !== undefined ? patch.priority : cur.priority
  };
  return serializeRegistryDocument(doc);
}

/** Appends a new tool row; promotes an empty document to {@code { tools: [...] }}. */
export function appendRegistryTool(registryRaw: string, row: UserToolRegistryRow): string {
  const doc = parseRegistryDocument(registryRaw);
  if (doc.tools.some((t) => t.id === row.id)) {
    throw new Error(`Tool id "${row.id}" already exists in registry.`);
  }
  doc.tools.push(row);
  if (doc.wrapper === 'empty') {
    doc.wrapper = 'tools';
  }
  return serializeRegistryDocument(doc);
}
