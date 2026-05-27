package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

/**
 * Batch page/component transform: bundled subgraph inner LLM + writes.
 * Off by default — prefer {@code ListContentDependencyScope} + {@code TranslateContentBatch}.
 */
class TransformContentSubgraphTool extends AbstractStudioAiTool {

  /** When false, this tool is not registered on the wire. */
  private static final boolean ENABLED_ON_WIRE = false

  @Override
  String wireName() { 'TransformContentSubgraph' }

  @Override
  String description() { ToolPrompts.getDESC_TRANSFORM_CONTENT_SUBGRAPH() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.TRANSFORM_CONTENT_SUBGRAPH }

  /** Off-wire unless {@link #ENABLED_ON_WIRE}; otherwise same gates as other inner-LLM translate tools. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return ENABLED_ON_WIRE &&
      (ctx.apiKeyForImages ?: '').toString().trim().length() > 0 &&
      !ctx.fullSuppressRepoWrites
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    return AiOrchestrationTools.runTransformContentSubgraph(
      ctx.ops,
      input,
      (ctx.apiKeyForImages ?: '').toString().trim(),
      (ctx.textModel ?: '').toString().trim() ?: 'gpt-4o-mini',
      ctx.normProtectedFormItemPath,
      ctx.pathProtectFormItem
    )
  }
}
