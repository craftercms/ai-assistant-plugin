/**
 * Server SSE pipeline timing on the terminal frame only ({@code metadata.completed} or {@code metadata.error}).
 * Ignore heartbeats and tool-progress rows — those are not completion summaries.
 */

export type PipelineTimingFields = {
  toolPipelineWallMs?: number;
  toolPipelineTotalSec?: number;
  toolPipelineTaskCompletionSec?: number;
};

/** Coerces JSON numbers or numeric strings (Groovy / proxies sometimes stringify). */
export function parseNonNegativeNumber(value: unknown): number | undefined {
  if (value == null || value === '') {
    return undefined;
  }
  const n = typeof value === 'number' ? value : typeof value === 'string' ? Number(value.trim()) : Number(value);
  if (!Number.isFinite(n) || n < 0) {
    return undefined;
  }
  return n;
}

/** True when the SSE row is a normal or error completion (not a mid-stream heartbeat/progress row). */
export function isTerminalMetadata(metadata: Record<string, unknown> | undefined): boolean {
  if (!metadata || typeof metadata !== 'object') {
    return false;
  }
  return metadata.completed === true || metadata.error === true;
}

/**
 * Mid-stream rows that must never contribute pipeline timing even if numeric keys are present.
 * Terminal frames may still carry a stale {@code status} from the last flux chunk — do not reject those.
 */
function isNonTerminalProgressStatus(metadata: Record<string, unknown>): boolean {
  const status = metadata.status != null ? String(metadata.status).trim() : '';
  if (!status) {
    return false;
  }
  if (status === 'pipeline-heartbeat' || status === 'tool-progress' || status === 'tool-workflow-hint') {
    return !isTerminalMetadata(metadata);
  }
  return false;
}

export function extractTerminalPipelineTiming(
  metadata: Record<string, unknown> | undefined
): PipelineTimingFields | null {
  if (!metadata || typeof metadata !== 'object') {
    return null;
  }
  if (!isTerminalMetadata(metadata)) {
    return null;
  }
  if (isNonTerminalProgressStatus(metadata)) {
    return null;
  }

  const wallRaw = parseNonNegativeNumber(metadata.toolPipelineWallMs);
  const wallMs = wallRaw != null ? Math.round(wallRaw) : undefined;
  const totalSec = parseNonNegativeNumber(metadata.toolPipelineTotalSec);
  const taskSec = parseNonNegativeNumber(metadata.toolPipelineTaskCompletionSec);

  if (wallMs === undefined && totalSec === undefined && taskSec === undefined) {
    return null;
  }

  return {
    ...(wallMs !== undefined ? { toolPipelineWallMs: wallMs } : {}),
    ...(totalSec !== undefined ? { toolPipelineTotalSec: totalSec } : {}),
    ...(taskSec !== undefined ? { toolPipelineTaskCompletionSec: taskSec } : {})
  };
}

/** Prefer newly extracted timing; keep existing message fields when the terminal frame has no timing keys. */
export function mergePipelineTimingFields<T extends PipelineTimingFields>(
  existing: T | undefined,
  incoming: PipelineTimingFields | null | undefined
): PipelineTimingFields {
  const out: PipelineTimingFields = {};
  const wall = incoming?.toolPipelineWallMs ?? existing?.toolPipelineWallMs;
  const total = incoming?.toolPipelineTotalSec ?? existing?.toolPipelineTotalSec;
  const task = incoming?.toolPipelineTaskCompletionSec ?? existing?.toolPipelineTaskCompletionSec;
  if (wall !== undefined) out.toolPipelineWallMs = wall;
  if (total !== undefined) out.toolPipelineTotalSec = total;
  if (task !== undefined) out.toolPipelineTaskCompletionSec = task;
  return out;
}
