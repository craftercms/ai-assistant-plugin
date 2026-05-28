package plugins.org.craftercms.aiassistant.studio.spi.tool

import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.studio.engine.rag.ExpertSkillVectorRegistry
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations

import java.util.Collections
import java.util.LinkedHashMap
import java.util.List
import java.util.Map

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
  /** Spring AI embedding model for {@code QueryExpertGuidance}; null when no expert skills or API key. */
  final Object expertEmbeddingModel
  /** Expert skill id → markdown corpus URL for {@code QueryExpertGuidance}. */
  final Map<String, String> expertUrlBySkillId
  /** True for {@link #forRecipeEngine} JVM confirmation/prefetch steps (no browser CrafterQ session). */
  final boolean recipeEngineRun

  /**
   * Private constructor; not for direct use.
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
    this.expertEmbeddingModel = b.expertEmbeddingModel
    this.expertUrlBySkillId = b.expertUrlBySkillId != null ?
      Collections.unmodifiableMap(new LinkedHashMap<>(b.expertUrlBySkillId)) :
      Collections.<String, String>emptyMap()
    this.recipeEngineRun = b.recipeEngineRun
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
    String embKey = (apiKeyForImages ?: '').toString().trim()
    Object expertEmbed = null
    Map<String, String> expertUrls = new LinkedHashMap<>()
    if (!experts.isEmpty() && embKey) {
      expertEmbed = ExpertSkillVectorRegistry.buildEmbeddingModel(embKey, cfg)
      for (Map m : experts) {
        String sid = m.skillId?.toString()?.trim()
        String u = m.url?.toString()?.trim()
        if (sid && u) {
          expertUrls.put(sid, u)
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
      .expertEmbeddingModel(expertEmbed)
      .expertUrlBySkillId(expertUrls)
      .build()
  }

  /**
   * Context for {@link plugins.org.craftercms.aiassistant.studio.engine.routing.subrouting.AuthoringIntentRecipeEngine}
   * prefetch and confirmation {@code engineSteps}. Loads site {@code tools.json} so built-in tools
   * see {@code builtInToolSettings} defaults (e.g. {@code defaultChannel}) like the main tools loop.
   */
  static StudioAiToolContext forRecipeEngine(StudioToolOperations ops) {
    if (ops == null) {
      throw new IllegalArgumentException('ops is required')
    }
    Map cfg = StudioAiAssistantProjectConfig.load(ops)
    return builder()
      .converter({ Object result, java.lang.reflect.Type rt -> result })
      .ops(ops)
      .aiProjectToolCfg(cfg instanceof Map ? cfg : [:])
      .recipeEngineRun(true)
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
    Object expertEmbeddingModel
    Map<String, String> expertUrlBySkillId
    boolean recipeEngineRun

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
    /** Embedding model for expert-skill RAG tools; returns {@code this}. */
    Builder expertEmbeddingModel(Object v) { this.expertEmbeddingModel = v; return this }
    /** Expert skill id → corpus URL map; returns {@code this}. */
    Builder expertUrlBySkillId(Map<String, String> v) { this.expertUrlBySkillId = v; return this }
    /** Marks recipe-engine prefetch/confirmation execution; returns {@code this}. */
    Builder recipeEngineRun(boolean v) { this.recipeEngineRun = v; return this }

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
