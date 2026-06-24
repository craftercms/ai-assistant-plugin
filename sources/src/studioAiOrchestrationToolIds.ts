/**
 * Built-in Studio AI orchestration tool names (Spring AI wire). Keep aligned with
 * {@code StudioAiToolRegistry.CORE_TOOLS} wire names (assembled per request via
 * {@code AiOrchestrationTools.build}).
 * Use {@link STUDIO_AI_MCP_ALL_TOKEN} in agent JSON to retain every dynamic {@code mcp_*} tool after site policy.
 */
export const STUDIO_AI_MCP_ALL_TOKEN = 'mcp:*';

/** Checkbox order for Project Tools → AI Assistant Agents (subset may be omitted at runtime). */
export const STUDIO_AI_BUILTIN_TOOL_IDS: readonly string[] = [
  'GenerateTextNoTools',
  'TranslateContentBatch',
  'TranslateContentItem',
  'TransformContentSubgraph',
  'ContentExists',
  'GetContent',
  'ListContentDependencyScope',
  'ListContentTranslationScope',
  'ListStudioContentTypes',
  'GetContentTypeFormDefinition',
  'GetContentVersionHistory',
  'FindContentVersion',
  'CompareContentVersions',
  'GetPreviewHtml',
  'FetchHttpUrl',
  'PostHttpUrl',
  'WebSearch',
  'SerpApiWebSearch',
  'SlackPostMessage',
  'ConsultCrafterQ',
  'ResearchSiteContent',
  'QueryExpertGuidance',
  'WriteContent',
  'ListPagesAndComponents',
  'update_template',
  'update_content',
  'update_content_type',
  'analyze_template',
  'publish_content',
  'GetCrafterizingPlaybook',
  'revert_change',
  'GenerateImage',
  'GeneratePlaceholderImage',
  'InvokeSiteUserTool',
  STUDIO_AI_MCP_ALL_TOKEN
] as const;

/** LLM values accepted by {@link StudioAiLlmKind#normalize} (POST / stream `llm`). */
export const STUDIO_AI_LLM_VENDOR_IDS: readonly string[] = [
  'openAI',
  'xAI',
  'deepSeek',
  'llama',
  'gemini',
  'claude',
  'script'
] as const;

export type StudioAiLlmVendorId = (typeof STUDIO_AI_LLM_VENDOR_IDS)[number];

/** User-facing label for Project Tools LLM provider ids (wire id stays {@link STUDIO_AI_LLM_VENDOR_IDS}). */
export function llmVendorDisplayLabel(vendorId: string): string {
  switch ((vendorId ?? '').trim()) {
    case 'openAI':
      return 'Compatible chat API';
    case 'claude':
      return 'Anthropic Claude';
    case 'xAI':
      return 'xAI';
    case 'deepSeek':
      return 'DeepSeek';
    case 'llama':
      return 'Ollama / local';
    case 'gemini':
      return 'Google Gemini';
    case 'script':
      return 'Custom script';
    default:
      return vendorId?.trim() || 'Compatible chat API';
  }
}

/** Default chat models for tools-loop providers (UI presets; server may accept other ids). */
export const STUDIO_AI_TOOLS_LOOP_CHAT_MODELS: readonly string[] = [
  'gpt-4o-mini',
  'gpt-4o',
  'gpt-4.1',
  'gpt-4.1-mini',
  'o3-mini',
  'o1',
  'o1-mini'
] as const;

/** @deprecated Use {@link STUDIO_AI_TOOLS_LOOP_CHAT_MODELS}. */
export const STUDIO_AI_OPENAI_WIRE_CHAT_MODELS = STUDIO_AI_TOOLS_LOOP_CHAT_MODELS;

export const STUDIO_AI_CLAUDE_CHAT_MODELS: readonly string[] = [
  'claude-sonnet-4-20250514',
  'claude-opus-4-20250514'
] as const;

export const STUDIO_AI_DEFAULT_IMAGE_MODEL = 'gpt-image-1';

/** Legacy OpenAI DALL·E ids are no longer valid on the Images API — map to {@link STUDIO_AI_DEFAULT_IMAGE_MODEL}. */
export function isDeprecatedDallEImageModel(raw: string): boolean {
  const m = raw.trim().toLowerCase().replace(/_/g, '-');
  return m.startsWith('dall-e') || m.startsWith('dalle');
}

/** Canonical image model for agents.json and stream POST; coerces deprecated DALL·E ids. */
export function normalizeImageModelId(raw: string | undefined | null): string | undefined {
  const trimmed = (raw ?? '').trim();
  if (!trimmed) return undefined;
  if (isDeprecatedDallEImageModel(trimmed)) return STUDIO_AI_DEFAULT_IMAGE_MODEL;
  return trimmed;
}
