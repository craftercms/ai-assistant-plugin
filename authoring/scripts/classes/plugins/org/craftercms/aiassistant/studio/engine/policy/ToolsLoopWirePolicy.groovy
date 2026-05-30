package plugins.org.craftercms.aiassistant.studio.engine.policy

/**
 * Immutable per-wire tools-loop behavior: progress category, repository mutation flags,
 * wire truncation mode, prose-JSON dispatch, and SSE pipeline stage.
 * <p>Instances are built only via package factories and registered in
 * {@link ToolsLoopWirePolicyRegistry}. {@link plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration}
 * must not branch on individual tool names for these concerns.</p>
 */
final class ToolsLoopWirePolicy {

  /** Progress-line bucket: read / inspect tools. */
  static final String PROGRESS_READ = 'read'
  /** Progress-line bucket: repository writes and edits. */
  static final String PROGRESS_WRITE = 'write'
  /** Progress-line bucket: template or structural analysis. */
  static final String PROGRESS_ANALYSIS = 'analysis'
  /** Progress-line bucket: everything else (site user tools, MCP, unknown wires). */
  static final String PROGRESS_OTHER = 'other'

  /** Default wire truncation for large JSON tool results. */
  static final String WIRE_TRUNCATE = 'truncate'
  /** Compact {@code update_content} payloads on the chat wire. */
  static final String WIRE_COMPACT_UPDATE_CONTENT = 'compact_update_content'
  /** Compact {@code GenerateImage} payloads (inline ref pattern). */
  static final String WIRE_COMPACT_GENERATE_IMAGE = 'compact_generate_image'
  /** Compact {@code FetchHttpUrl} payloads (cap HTML body on the chat wire). */
  static final String WIRE_COMPACT_FETCH_HTTP = 'compact_fetch_http'

  /** Maps to 🔍 / ✏️ / 📈 / 🔄 via {@link ToolsLoopWirePolicyRegistry#progressCategoryEmoji}. */
  final String progressCategory
  /** When true, a successful call may end the “task” phase of pipeline timing. */
  final boolean repositoryMutation
  /** When true, tool-progress lines use the expert prefix (🛠️🤓). */
  final boolean expertGuidancePrefix
  /** When true, skip the tool if an earlier WriteContent in the same round failed. */
  final boolean skipWhenPriorWriteFailedInRound
  /** When true, track write paths and duplicate-write suppression for this wire. */
  final boolean duplicateWritePathGuard
  /** When true, allow at most one successful {@code GenerateImage} per chat turn. */
  final boolean duplicateGenerateImageThisTurnGuard
  /** One of {@link #WIRE_TRUNCATE}, {@link #WIRE_COMPACT_UPDATE_CONTENT}, {@link #WIRE_COMPACT_GENERATE_IMAGE}. */
  final String wireOutputMode
  /** When set, fenced JSON with this key dispatches to this wire (e.g. {@code toolId} → site user tools). */
  final String proseJsonDispatchKey
  /** Optional args normalizer id; {@code write_content} runs {@link plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools#normalizeWriteContentToolArgsJson}. */
  final String normalizeArgsId
  /** {@code main} or {@code verification} for SSE pipeline grouping. */
  final String pipelineStage
  /** Run preview phrase verification enrichment on tool JSON (GetPreviewHtml). */
  final boolean enrichPreviewHtmlResult

  /** Package-private; use static factories only. */
  private ToolsLoopWirePolicy(
    String progressCategory,
    boolean repositoryMutation,
    boolean expertGuidancePrefix,
    boolean skipWhenPriorWriteFailedInRound,
    boolean duplicateWritePathGuard,
    boolean duplicateGenerateImageThisTurnGuard,
    String wireOutputMode,
    String proseJsonDispatchKey,
    String normalizeArgsId,
    String pipelineStage,
    boolean enrichPreviewHtmlResult
  ) {
    this.progressCategory = progressCategory ?: PROGRESS_OTHER
    this.repositoryMutation = repositoryMutation
    this.expertGuidancePrefix = expertGuidancePrefix
    this.skipWhenPriorWriteFailedInRound = skipWhenPriorWriteFailedInRound
    this.duplicateWritePathGuard = duplicateWritePathGuard
    this.duplicateGenerateImageThisTurnGuard = duplicateGenerateImageThisTurnGuard
    this.wireOutputMode = wireOutputMode ?: WIRE_TRUNCATE
    this.proseJsonDispatchKey = proseJsonDispatchKey
    this.normalizeArgsId = normalizeArgsId
    this.pipelineStage = pipelineStage ?: 'main'
    this.enrichPreviewHtmlResult = enrichPreviewHtmlResult
  }

  /** Default policy for unknown built-ins and {@code mcp_*} wires. */
  static ToolsLoopWirePolicy defaults() {
    return new ToolsLoopWirePolicy(PROGRESS_OTHER, false, false, false, false, false, WIRE_TRUNCATE, null, null, 'main', false)
  }

  /** Standard read / search / inspect tools. */
  static ToolsLoopWirePolicy readPolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_READ, false, false, false, false, false, WIRE_TRUNCATE, null, null, 'main', false)
  }

  /** Expert SME tools (QueryExpertGuidance, GetCrafterizingPlaybook). */
  static ToolsLoopWirePolicy expertReadPolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_READ, false, true, false, false, false, WIRE_TRUNCATE, null, null, 'main', false)
  }

  /** Template analysis ({@code analyze_template}). */
  static ToolsLoopWirePolicy analysisPolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_ANALYSIS, false, false, false, false, false, WIRE_TRUNCATE, null, null, 'verification', false)
  }

  /**
   * Repository mutation tools (revert, publish, translate, etc.) that persist without a separate WriteContent hop.
   * @param duplicatePathGuard when true, enable per-turn duplicate path suppression (WriteContent uses dedicated policy).
   */
  static ToolsLoopWirePolicy writeMutationPolicy(boolean duplicatePathGuard = false) {
    return new ToolsLoopWirePolicy(PROGRESS_WRITE, true, false, false, duplicatePathGuard, false, WIRE_TRUNCATE, null, null, 'main', false)
  }

  /**
   * Preparatory {@code update_content} / {@code update_template} / {@code update_content_type}: loads current
   * artifact + returns WriteContent guidance — does not persist. {@code repositoryMutation} stays false so the
   * tools loop does not enter verification ("Checking the result") until after {@link #writeContentPolicy}.
   */
  static ToolsLoopWirePolicy preparatoryUpdatePolicy(String wireOutputMode = WIRE_TRUNCATE) {
    return new ToolsLoopWirePolicy(PROGRESS_WRITE, false, false, false, false, false, wireOutputMode, null, null, 'main', false)
  }

  /** GetPreviewHtml: verification stage; may skip after failed write; enriches result JSON. */
  static ToolsLoopWirePolicy verificationReadPolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_READ, false, false, true, false, false, WIRE_TRUNCATE, null, null, 'verification', true)
  }

  /** GeneratePlaceholderImage: Studio sample placeholder; not a repository mutation. */
  static ToolsLoopWirePolicy placeholderImagePolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_WRITE, false, false, false, false, false, WIRE_TRUNCATE, null, null, 'main', false)
  }

  /** GenerateImage: compact wire output; not counted as a repository XML mutation. */
  static ToolsLoopWirePolicy generateImagePolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_WRITE, false, false, false, false, true, WIRE_COMPACT_GENERATE_IMAGE, null, null, 'main', false)
  }

  /** FetchHttpUrl: cap large HTML bodies on the tools-loop wire to avoid context overflow. */
  static ToolsLoopWirePolicy fetchHttpUrlPolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_READ, false, false, false, false, false, WIRE_COMPACT_FETCH_HTTP, null, null, 'main', false)
  }

  /** InvokeSiteUserTool: prose blocks may use {@code {"toolId":"…"}} instead of API tool_calls. */
  static ToolsLoopWirePolicy siteUserToolPolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_OTHER, false, false, false, false, false, WIRE_TRUNCATE, 'toolId', null, 'main', false)
  }

  /** WriteContent: full duplicate-path guard and write_content arg normalization. */
  static ToolsLoopWirePolicy writeContentPolicy() {
    return new ToolsLoopWirePolicy(PROGRESS_WRITE, true, false, false, true, false, WIRE_TRUNCATE, null, 'write_content', 'main', false)
  }
}
