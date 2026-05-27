package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.catalog.StudioAiImageGeneratorFactory
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.engine.turn.chatcompletions.ChatCompletionsToolWire
import plugins.org.craftercms.aiassistant.spi.imagegen.StudioAiImageGenContext
import plugins.org.craftercms.aiassistant.spi.imagegen.StudioAiImageGenerator
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

/**
 * Generates an image via the configured image backend for this agent/site.
 */
class GenerateImageTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'GenerateImage' }

  @Override
  String description() { ToolPrompts.getDESC_GENERATE_IMAGE() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GENERATE_IMAGE }

  /** Registered when {@link StudioAiImageGeneratorFactory} resolves an image backend for this session. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return resolveGenerator(ctx) != null
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    StudioAiImageGenerator imageGen = resolveGenerator(ctx)
    if (imageGen == null) {
      throw new IllegalStateException('GenerateImage is not configured for this session')
    }
    StudioAiImageGenContext imageCtx = buildImageContext(ctx)
    return ChatCompletionsToolWire.enrichGenerateImageToolResult(
      imageGen.generate((Map) (input ?: [:]), imageCtx) as Map
    )
  }

  /**
   * Resolves generator from request and plugin context.
   * @param ctx Caller-supplied input.
   * @return StudioAiImageGenerator result.
   */
  private static StudioAiImageGenerator resolveGenerator(StudioAiToolContext ctx) {
    return StudioAiImageGeneratorFactory.resolve(
      ctx.ops,
      (ctx.llmNormalized ?: '').toString(),
      (ctx.imageGeneratorParam ?: '').toString().trim(),
      (ctx.apiKeyForImages ?: '').toString().trim(),
      ctx.imageModel
    )
  }

  /**
   * Builds image context for tool or orchestration output.
   * @param ctx Caller-supplied input.
   * @return StudioAiImageGenContext result.
   */
  private static StudioAiImageGenContext buildImageContext(StudioAiToolContext ctx) {
    return StudioAiImageGeneratorFactory.buildContext(
      ctx.ops,
      (ctx.llmNormalized ?: '').toString(),
      (ctx.imageGeneratorParam ?: '').toString().trim(),
      (ctx.apiKeyForImages ?: '').toString().trim(),
      ctx.imageModel
    )
  }
}
