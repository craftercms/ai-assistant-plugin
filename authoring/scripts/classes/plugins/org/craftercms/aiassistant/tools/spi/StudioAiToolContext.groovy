package plugins.org.craftercms.aiassistant.tools.spi

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Shared build-time and execute-time state for {@link StudioAiOrchestrationTool} implementations.
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

  static Builder builder() {
    return new Builder()
  }

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

  /** Minimal context for recipe-engine prefetch (read-only SPI tools only). */
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

    Builder converter(Object v) { this.converter = v; return this }
    Builder ops(StudioToolOperations v) { this.ops = v; return this }
    Builder toolProgressListener(Closure v) { this.toolProgressListener = v; return this }
    Builder apiKeyForImages(String v) { this.apiKeyForImages = v; return this }
    Builder imageModel(String v) { this.imageModel = v; return this }
    Builder fullSuppressRepoWrites(boolean v) { this.fullSuppressRepoWrites = v; return this }
    Builder normProtectedFormItemPath(String v) { this.normProtectedFormItemPath = v; return this }
    Builder pathProtectFormItem(boolean v) { this.pathProtectFormItem = v; return this }
    Builder aiProjectToolCfg(Map v) { this.aiProjectToolCfg = v; return this }
    Builder expertSkillSpecs(List<Map> v) { this.expertSkillSpecs = v; return this }
    Builder textModel(String v) { this.textModel = v; return this }
    Builder llmNormalized(String v) { this.llmNormalized = v; return this }
    Builder imageGeneratorParam(String v) { this.imageGeneratorParam = v; return this }
    Builder agentEnabledBuiltInTools(Collection v) { this.agentEnabledBuiltInTools = v; return this }

    StudioAiToolContext build() {
      if (ops == null) {
        throw new IllegalArgumentException('ops is required')
      }
      return new StudioAiToolContext(this)
    }
  }
}
