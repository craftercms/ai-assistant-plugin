/** Autonomous agent rows from `config/studio/ai-assistant/agents.json` (`mode: autonomous`). */
import {
  normalizeAgentSkillsRaw,
  agentSkillsForRequest,
  type AgentSkillConfig,
  normalizeEnabledBuiltInToolsRaw
} from './agentConfig';

export type AutonomousScope = 'user' | 'role' | 'project';

export interface AutonomousAgentDefinition {
  name: string;
  /** Quartz-style expression (server prototype maps common patterns to periods). */
  schedule: string;
  prompt: string;
  scope: AutonomousScope;
  llm: string;
  llmModel: string;
  imageModel?: string;
  /** Optional GenerateImage backend (same semantics as interactive chat **imageGenerator**). */
  imageGenerator?: string;
  llmApiKey?: string;
  /** {@code secrets.json} entry id for LLM credentials (not used for script LLMs). */
  llmSecretKey?: string;
  /**
   * When true, the model may set `ownerAgentId` on new human tasks and dismiss/complete tasks owned by other agents.
   * Default false: only this agent’s tasks are modified.
   */
  manageOtherAgentsHumanTasks?: boolean;
  /**
   * When false, the server registers the agent as **stopped** after sync until an author uses **Start** in the widget.
   * Default true: status **waiting** after sync (still requires **Start system** + supervisor for ticks to run).
   */
  startAutomatically?: boolean;
  /**
   * When true (default), a failed run sets the agent to **error** until cleared; other agents and the supervisor keep running.
   * When false, the failure is recorded on state but the agent returns to **waiting** with **next step due** so the next tick retries.
   */
  stopOnFailure?: boolean;
  /** Optional markdown URL skills; only **enabled** rows are synced for **QueryExpertGuidance**. */
  skills?: AgentSkillConfig[];
  /**
   * Optional subset of built-in tool wire names for autonomous runs (same as chat stream **enabledBuiltInTools**).
   * Include **mcp:*** to keep all MCP tools.
   */
  enabledBuiltInTools?: string[];
}

/** Mirrors `AutonomousAgentIdBuilder` (Groovy) for project-scoped ids used in sync/status. */
export function normalizeAgentNameForId(raw: string): string {
  let s = (raw ?? '').trim().toLowerCase();
  s = s.replace(/\s+/g, '-');
  s = s.replace(/[^a-z0-9-]+/g, '-');
  s = s.replace(/-+/g, '-');
  s = s.replace(/^-+|-+$/g, '');
  return s || 'agent';
}

export function sanitizeScopeIdForAgentId(scopeId: string): string {
  return scopeId.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/-+/g, '-');
}

/**
 * Same shape as {@link AutonomousAgentIdBuilder.buildAgentId} in the autonomous Groovy package.
 */
export function buildAutonomousAgentId(
  projectId: string,
  scope: AutonomousScope,
  scopeId: string,
  agentName: string
): string {
  const p = (projectId || 'default').trim();
  let sc: AutonomousScope = scope;
  if (sc !== 'user' && sc !== 'role' && sc !== 'project') sc = 'project';
  const sid = sanitizeScopeIdForAgentId((scopeId || p).trim());
  const n = normalizeAgentNameForId(agentName);
  return `${p}-${sc}-${sid}-${n}`;
}

/** One row for the autonomous UI table — either from `/status` or merged from configuration. */
export type AutonomousTableAgentRow = {
  agentId: string;
  definition?: Record<string, unknown>;
  state?: Record<string, unknown>;
  pastRunReports?: unknown[];
  /** Present when the row is built from agents.json before the server registry lists it (e.g. before sync). */
  syntheticFromConfig?: boolean;
};

/** Viewer context so client-built agent ids match {@code sync.post.groovy} (user/role scopeId). */
export type AutonomousMergeViewer = {
  username: string;
  /** First role id for the active site (best-effort mirror of sync’s first {@code ROLE_*} authority). */
  roleScopeId: string;
};

/**
 * Ensures every configured agent appears in the table even when `/status` returns an empty `agents`
 * list (registry not yet populated). Matches server rows by `agentId` or `definition.name`, then
 * synthesizes rows with the same id {@link buildAutonomousAgentId} uses on sync.
 */
export function mergeAutonomousAgentsForTable(
  siteId: string,
  defs: AutonomousAgentDefinition[],
  statusAgents: AutonomousTableAgentRow[] | undefined | null,
  viewer?: AutonomousMergeViewer
): AutonomousTableAgentRow[] {
  const list = Array.isArray(statusAgents) ? statusAgents : [];
  const taken = new Set<string>();
  const out: AutonomousTableAgentRow[] = [];

  const vUsername = (viewer?.username ?? '').trim() || 'anonymous';
  const vRole = (viewer?.roleScopeId ?? viewer?.username ?? siteId).trim() || siteId;

  const syntheticRow = (d: AutonomousAgentDefinition, id: string): AutonomousTableAgentRow => ({
    agentId: id,
    definition: {
      name: d.name,
      schedule: d.schedule,
      scope: d.scope,
      prompt: d.prompt,
      llm: d.llm,
      llmModel: d.llmModel,
      ...(d.imageModel != null ? { imageModel: d.imageModel } : {}),
      ...(d.imageGenerator != null && String(d.imageGenerator).trim() !== ''
        ? { imageGenerator: String(d.imageGenerator).trim() }
        : {}),
      ...(d.manageOtherAgentsHumanTasks ? { manageOtherAgentsHumanTasks: true } : {}),
      ...(d.startAutomatically === false ? { startAutomatically: false } : {}),
      ...(d.stopOnFailure === false ? { stopOnFailure: false } : {}),
      ...(agentSkillsForRequest(d) ? { skills: agentSkillsForRequest(d) } : {}),
      siteId
    },
    state: { status: 'pending' },
    syntheticFromConfig: true
  });

  for (const d of defs) {
    const name = d.name;
    const byName = list.find((a) => !taken.has(a.agentId) && String(a.definition?.name ?? '') === name);
    if (byName) {
      taken.add(byName.agentId);
      out.push(byName);
      continue;
    }
    const nn = normalizeAgentNameForId(name);
    const byNorm = list.find((a) => {
      if (taken.has(a.agentId)) return false;
      const def = a.definition;
      const raw =
        def && typeof def === 'object'
          ? String((def as Record<string, unknown>).name ?? (def as Record<string, unknown>).label ?? '')
          : '';
      return normalizeAgentNameForId(raw) === nn;
    });
    if (byNorm) {
      taken.add(byNorm.agentId);
      out.push(byNorm);
      continue;
    }

    let id: string;
    if (d.scope === 'project') {
      id = buildAutonomousAgentId(siteId, 'project', siteId, name);
    } else if (d.scope === 'user') {
      id = buildAutonomousAgentId(siteId, 'user', vUsername, name);
    } else {
      id = buildAutonomousAgentId(siteId, 'role', vRole, name);
    }

    const hit = list.find((a) => a.agentId === id && !taken.has(a.agentId));
    if (hit) {
      taken.add(hit.agentId);
      out.push(hit);
    } else {
      out.push(syntheticRow(d, id));
    }
  }

  for (const a of list) {
    if (!taken.has(a.agentId)) {
      out.push(a);
    }
  }
  return out;
}

function normalizeManageOtherAgentsHumanTasks(raw: unknown): boolean | undefined {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no' || s === '') return false;
  }
  return undefined;
}

function normalizeStartAutomatically(raw: unknown): boolean | undefined {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no') return false;
  }
  return undefined;
}

function normalizeStopOnFailure(raw: unknown): boolean | undefined {
  if (raw === true || raw === 1) return true;
  if (raw === false || raw === 0) return false;
  if (typeof raw === 'string') {
    const s = raw.trim().toLowerCase();
    if (s === 'true' || s === '1' || s === 'yes') return true;
    if (s === 'false' || s === '0' || s === 'no') return false;
  }
  return undefined;
}

function normalizeOne(raw: unknown): AutonomousAgentDefinition | null {
  if (raw == null || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  const name = String(o.name ?? o.label ?? '').trim();
  if (!name) return null;
  const scopeRaw = String(o.scope ?? 'project').trim().toLowerCase();
  const scope: AutonomousScope =
    scopeRaw === 'user' || scopeRaw === 'role' || scopeRaw === 'project' ? scopeRaw : 'project';
  const manageCross = normalizeManageOtherAgentsHumanTasks(o.manageOtherAgentsHumanTasks);
  const startAuto = normalizeStartAutomatically(
    o.startAutomatically ?? o.start_automatically ?? o.automaticallyStart ?? o.automatically_start
  );
  const stopFail = normalizeStopOnFailure(o.stopOnFailure ?? o.stop_on_failure);
  const skillsParsed = normalizeAgentSkillsRaw(o.skills);
  const skillsForSync = agentSkillsForRequest({ skills: skillsParsed });
  const enabledBuiltIn = normalizeEnabledBuiltInToolsRaw(o.enabledBuiltInTools ?? o.enabled_built_in_tools);
  return {
    name,
    schedule: String(o.schedule ?? '0 0 * * * ?').trim(),
    prompt: String(o.prompt ?? '').trim(),
    scope,
    llm: String(o.llm ?? 'openAI').trim(),
    llmModel: String(o.llmModel ?? 'gpt-4o-mini').trim(),
    imageModel: o.imageModel != null ? String(o.imageModel).trim() : undefined,
    imageGenerator:
      o.imageGenerator != null && String(o.imageGenerator).trim() !== ''
        ? String(o.imageGenerator).trim()
        : undefined,
    llmApiKey: o.llmApiKey != null ? String(o.llmApiKey).trim() : undefined,
    ...(manageCross !== undefined ? { manageOtherAgentsHumanTasks: manageCross } : {}),
    ...(startAuto === false ? { startAutomatically: false } : {}),
    ...(stopFail === false ? { stopOnFailure: false } : {}),
    ...(skillsForSync && skillsForSync.length > 0 ? { skills: skillsForSync } : {}),
    ...(enabledBuiltIn?.length ? { enabledBuiltInTools: enabledBuiltIn } : {})
  };
}

/** Merge nested `configuration` with root props (Studio spreads widget configuration onto the component). */
export function mergeAutonomousWidgetProps(props: Record<string, unknown>): Record<string, unknown> {
  const nested =
    props.configuration != null && typeof props.configuration === 'object' && !Array.isArray(props.configuration)
      ? (props.configuration as Record<string, unknown>)
      : {};
  return { ...nested, ...props };
}
