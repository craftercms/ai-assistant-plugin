package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * Inner LLM + write for a single repository XML path ({@code maxItems=1}).
 */
class TranslateContentItemTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'TranslateContentItem' }

  @Override
  String description() { ToolPrompts.getDESC_TRANSLATE_CONTENT_ITEM() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.TRANSLATE_CONTENT_ITEM }

  /** Registered when an API key is present and repository writes are not fully suppressed. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return (ctx.apiKeyForImages ?: '').toString().trim().length() > 0 && !ctx.fullSuppressRepoWrites
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    Map m = new LinkedHashMap<>((Map) (input ?: [:]))
    m.put('maxItems', Integer.valueOf(1))
    m.put('maxDepth', Integer.valueOf(0))
    return AiOrchestrationTools.runTransformContentSubgraph(
      ctx.ops,
      m,
      (ctx.apiKeyForImages ?: '').toString().trim(),
      (ctx.textModel ?: '').toString().trim() ?: 'gpt-4o-mini',
      ctx.normProtectedFormItemPath,
      ctx.pathProtectFormItem,
      'TranslateContentItem',
      'translate_content_item'
    )
  }
}
