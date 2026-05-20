package plugins.org.craftercms.aiassistant.tools.spi

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Shared build-time and execute-time state for {@link StudioAiOrchestrationTool} implementations.
 * Snapshots Studio beans, converters, and agent-scoped safeguards once per orchestration turn.
 * Produced exclusively through {@link Builder} helpers so tools and prefetch engines stay aligned.
 */
class StudioAiToolContext {

  final Object converter
  final StudioToolOperations ops
  final Closure toolProgressListener
  final String apiKeyForImages
  final String imageModel
  final boolean fullSuppressRepoWrites
  final String normProtectedFormItemPath
  final boolean pathProtectFormItem
  final Map aiProjectToolCfg
  final List<Map> expertSkillSpecs
  final String textModel
  final String llmNormalized
  final String imageGeneratorParam
  final Collection agentEnabledBuiltInTools

  /**
   * Copies immutable fields from {@link Builder} after validation so orchestration sees stable snapshots.
   * Applies defensive defaults ({@code [:]}, empty lists) where builders omit optional structures.
   */
  private StudioAiToolContext(Builder b) {
    this.converter = b.converter
    this.ops = b.ops
    this.toolProgressListener = b.toolProgressListener
    this.apiKeyForImages = b.apiKeyForImages
    this.imageModel = b.imageModel
    this.fullSuppressRepoWrites = b.fullSuppressRepoWrites
    this.normProtectedFormItemPath = b.normProtectedFormItemPath
    this.pathProtectFormItem = b.pathProtectFormItem
    this.aiProjectToolCfg = b.aiProjectToolCfg ?: [:]
    this.expertSkillSpecs = b.expertSkillSpecs ?: []
    this.textModel = b.textModel
    this.llmNormalized = b.llmNormalized
    this.imageGeneratorParam = b.imageGeneratorParam
    this.agentEnabledBuiltInTools = b.agentEnabledBuiltInTools
  }

  /**
   * Starts fluent construction of {@link StudioAiToolContext}.
   * Returns a fresh {@link Builder} with null defaults cleared later during {@link Builder#build}.
   */
  static Builder builder() {
    return new Builder()
  }

  /**
   * Factory used by orchestration after loading project configuration and normalizing protected form paths.
   * Sanitizes expert skill specs into a concrete List&lt;Map&gt;.
   * Delegates to {@link #builder()} for the actual wire-up.
   */
  static StudioAiToolContext fromBuildParams(
    Object converter,
    StudioToolOperations ops,
    Closure toolProgressListener = null,
    String apiKeyForImages = null,
    String imageModel = null,
    boolean fullSuppressRepoWrites = false,
    String protectedFormItemPath = null,
    List<Map> expertSkillSpecs = null,
    String textModel = null,
    String llmNormalized = null,
    String imageGeneratorParam = null,
    Collection agentEnabledBuiltInTools = null
  ) {
    Map cfg = StudioAiAssistantProjectConfig.load(ops)
    String normProtected = AuthoringPreviewContext.normalizeRepoPath(protectedFormItemPath)
    boolean pathProtect = (normProtected?.length() ?: 0) > 0
    List<Map> experts = []
    if (expertSkillSpecs instanceof List) {
      for (Object o : (List) expertSkillSpecs) {
        if (o instanceof Map) {
          experts.add((Map) o)
        }
      }
    }
    return builder()
      .converter(converter)
      .ops(ops)
      .toolProgressListener(toolProgressListener)
      .apiKeyForImages(apiKeyForImages)
      .imageModel(imageModel)
      .fullSuppressRepoWrites(fullSuppressRepoWrites)
      .normProtectedFormItemPath(normProtected)
      .pathProtectFormItem(pathProtect)
      .aiProjectToolCfg(cfg)
      .expertSkillSpecs(experts)
      .textModel(textModel)
      .llmNormalized(llmNormalized)
      .imageGeneratorParam(imageGeneratorParam)
      .agentEnabledBuiltInTools(agentEnabledBuiltInTools)
      .build()
  }

  /**
   * Minimal context for AuthoringIntentRecipeEngine prefetch steps (read-only SPI tools).
   * Supplies identity converter plus {@link StudioToolOperations} without optional chat metadata.
   * Throws early when ops is null so recipe bindings never run headless.
   */
  static StudioAiToolContext forRecipeEngine(StudioToolOperations ops) {
    if (ops == null) {
      throw new IllegalArgumentException('ops is required')
    }
    return builder()
      .converter({ Object result, java.lang.reflect.Type rt -> result })
      .ops(ops)
      .build()
  }

  static final class Builder {
    Object converter
    StudioToolOperations ops
    Closure toolProgressListener
    String apiKeyForImages
    String imageModel
    boolean fullSuppressRepoWrites
    String normProtectedFormItemPath
    boolean pathProtectFormItem
    Map aiProjectToolCfg
    List<Map> expertSkillSpecs
    String textModel
    String llmNormalized
    String imageGeneratorParam
    Collection agentEnabledBuiltInTools

    /** Assigns Spring AI {@code toolCallResultConverter} callback; returns {@code this}. */
    Builder converter(Object v) { this.converter = v; return this }
    /** Binds Studio repository/HTTP helpers; returns {@code this}. */
    Builder ops(StudioToolOperations v) { this.ops = v; return this }
    /** Stores optional SSE progress closure; returns {@code this}. */
    Builder toolProgressListener(Closure v) { this.toolProgressListener = v; return this }
    /** Persists vendor API key material for bitmap generation; returns {@code this}. */
    Builder apiKeyForImages(String v) { this.apiKeyForImages = v; return this }
    /** Sets configured OpenAI vendor image SKU; returns {@code this}. */
    Builder imageModel(String v) { this.imageModel = v; return this }
    /** Mirrors servlet attribute suppressing repo writes; returns {@code this}. */
    Builder fullSuppressRepoWrites(boolean v) { this.fullSuppressRepoWrites = v; return this }
    /** Stores normalized {@code formEngineItemPath}; returns {@code this}. */
    Builder normProtectedFormItemPath(String v) { this.normProtectedFormItemPath = v; return this }
    /** Flags whether path-level protections apply; returns {@code this}. */
    Builder pathProtectFormItem(boolean v) { this.pathProtectFormItem = v; return this }
    /** Embeds merged plugin tool JSON configuration; returns {@code this}. */
    Builder aiProjectToolCfg(Map v) { this.aiProjectToolCfg = v; return this }
    /** Records expert skill specs advertised to the LLM; returns {@code this}. */
    Builder expertSkillSpecs(List<Map> v) { this.expertSkillSpecs = v; return this }
    /** Captures resolved chat text model id; returns {@code this}. */
    Builder textModel(String v) { this.textModel = v; return this }
    /** Stores normalized llm transport token; returns {@code this}. */
    Builder llmNormalized(String v) { this.llmNormalized = v; return this }
    /** Mirrors POST {@code imageGenerator} hints; returns {@code this}. */
    Builder imageGeneratorParam(String v) { this.imageGeneratorParam = v; return this }
    /** Tracks filtered built-in tools for this agent/session; returns {@code this}. */
    Builder agentEnabledBuiltInTools(Collection v) { this.agentEnabledBuiltInTools = v; return this }

    /**
     * Validates mandatory {@link #ops} then freezes immutable {@link StudioAiToolContext}.
     * Throws {@link IllegalArgumentException} when ops is absent.
     * Returns ready-to-run context for tools.
     */
    StudioAiToolContext build() {
      if (ops == null) {
        throw new IllegalArgumentException('ops is required')
      }
      return new StudioAiToolContext(this)
    }
  }
}
