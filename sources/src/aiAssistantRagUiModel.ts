/** Site {@code tools.json} RAG blocks — mirrors {@link StudioAiAssistantProjectConfig} defaults. */

export type PluginRagMode = 'off' | 'supplement' | 'replace';

export interface PluginRagFormState {
  mode: PluginRagMode;
  kernelMaxChars: number;
  topK: number;
  maxAppendChars: number;
  maxChunkChars: number;
  maxChunks: number;
  embedBatchSize: number;
}

export interface AgentSkillsRagFormState {
  maxSkills: number;
  embeddingModel: string;
  maxChunks: number;
  maxChunkChars: number;
}

export const PLUGIN_RAG_DEFAULTS: PluginRagFormState = {
  mode: 'off',
  kernelMaxChars: 5200,
  topK: 8,
  maxAppendChars: 14000,
  maxChunkChars: 1800,
  maxChunks: 400,
  embedBatchSize: 64
};

export const AGENT_SKILLS_RAG_DEFAULTS: AgentSkillsRagFormState = {
  maxSkills: 12,
  embeddingModel: 'text-embedding-3-small',
  maxChunks: 400,
  maxChunkChars: 1800
};

const PLUGIN_RAG_KNOWN_KEYS = new Set([
  'mode',
  'kernelMaxChars',
  'topK',
  'maxAppendChars',
  'maxChunkChars',
  'maxChunks',
  'embedBatchSize'
]);

const AGENT_SKILLS_RAG_KNOWN_KEYS = new Set(['maxSkills', 'embeddingModel', 'maxChunks', 'maxChunkChars']);

function clampInt(n: number, min: number, max: number): number {
  if (!Number.isFinite(n)) return min;
  return Math.max(min, Math.min(max, Math.round(n)));
}

function parsePluginRagMode(raw: unknown): PluginRagMode {
  const s = String(raw ?? '').trim().toLowerCase();
  if (s === 'supplement' || s === 'replace') return s;
  return 'off';
}

export function parsePluginRagFromUnknown(raw: unknown): PluginRagFormState {
  const base = { ...PLUGIN_RAG_DEFAULTS };
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return base;
  }
  const o = raw as Record<string, unknown>;
  return {
    mode: parsePluginRagMode(o.mode),
    kernelMaxChars: clampInt(Number(o.kernelMaxChars ?? base.kernelMaxChars), 1024, 16000),
    topK: clampInt(Number(o.topK ?? base.topK), 1, 24),
    maxAppendChars: clampInt(Number(o.maxAppendChars ?? base.maxAppendChars), 2000, 80000),
    maxChunkChars: clampInt(Number(o.maxChunkChars ?? base.maxChunkChars), 512, 8000),
    maxChunks: clampInt(Number(o.maxChunks ?? base.maxChunks), 8, 2000),
    embedBatchSize: clampInt(Number(o.embedBatchSize ?? base.embedBatchSize), 8, 128)
  };
}

export function parseAgentSkillsRagFromUnknown(raw: unknown): AgentSkillsRagFormState {
  const base = { ...AGENT_SKILLS_RAG_DEFAULTS };
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return base;
  }
  const o = raw as Record<string, unknown>;
  const model = String(o.embeddingModel ?? base.embeddingModel).trim() || base.embeddingModel;
  return {
    maxSkills: clampInt(Number(o.maxSkills ?? base.maxSkills), 1, 32),
    embeddingModel: model,
    maxChunks: clampInt(Number(o.maxChunks ?? base.maxChunks), 8, 2000),
    maxChunkChars: clampInt(Number(o.maxChunkChars ?? base.maxChunkChars), 512, 8000)
  };
}

export function pluginRagToJsonObject(state: PluginRagFormState): Record<string, unknown> {
  return {
    mode: state.mode,
    kernelMaxChars: state.kernelMaxChars,
    topK: state.topK,
    maxAppendChars: state.maxAppendChars,
    maxChunkChars: state.maxChunkChars,
    maxChunks: state.maxChunks,
    embedBatchSize: state.embedBatchSize
  };
}

export function agentSkillsRagToJsonObject(state: AgentSkillsRagFormState): Record<string, unknown> {
  return {
    maxSkills: state.maxSkills,
    embeddingModel: state.embeddingModel.trim() || AGENT_SKILLS_RAG_DEFAULTS.embeddingModel,
    maxChunks: state.maxChunks,
    maxChunkChars: state.maxChunkChars
  };
}

export function validateRagPolicy(
  pluginRag: PluginRagFormState,
  agentSkillsRag: AgentSkillsRagFormState
): { ok: true } | { ok: false; message: string } {
  if (!agentSkillsRag.embeddingModel.trim()) {
    return { ok: false, message: 'Agent skills embedding model must not be empty.' };
  }
  return { ok: true };
}

export { PLUGIN_RAG_KNOWN_KEYS, AGENT_SKILLS_RAG_KNOWN_KEYS };
