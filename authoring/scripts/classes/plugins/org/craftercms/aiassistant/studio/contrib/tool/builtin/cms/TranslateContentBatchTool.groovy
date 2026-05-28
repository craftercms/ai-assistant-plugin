package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * Parallel inner LLM + write for many repository XML paths (same instructions).
 */
class TranslateContentBatchTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'TranslateContentBatch' }

  @Override
  String description() { ToolPrompts.getDESC_TRANSLATE_CONTENT_BATCH() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.TRANSLATE_CONTENT_BATCH }

  /** Registered when an API key is present and repository writes are not fully suppressed. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return innerLlmAvailable(ctx)
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    return AiOrchestrationTools.runTranslateContentBatchParallel(
      ctx.ops,
      (Map) (input ?: [:]),
      (ctx.apiKeyForImages ?: '').toString().trim(),
      defaultInnerChatModel(ctx),
      ctx.normProtectedFormItemPath,
      ctx.pathProtectFormItem,
      ctx.toolProgressListener
    )
  }

  /**
   * Inner llm available.
   * @param ctx Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean innerLlmAvailable(StudioAiToolContext ctx) {
    return (ctx.apiKeyForImages ?: '').toString().trim().length() > 0 && !ctx.fullSuppressRepoWrites
  }

  /**
   * Default inner chat model.
   * @param ctx Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String defaultInnerChatModel(StudioAiToolContext ctx) {
    return (ctx.textModel ?: '').toString().trim() ?: 'gpt-4o-mini'
  }
}
