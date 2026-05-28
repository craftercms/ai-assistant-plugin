package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.general

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * One-shot inner chat completion (no further function tools on that HTTP request).
 */
class GenerateTextNoToolsTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'GenerateTextNoTools' }

  @Override
  String description() { ToolPrompts.getDESC_GENERATE_TEXT_NO_TOOLS() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GENERATE_TEXT_NO_TOOLS }

  /** Registered when an LLM API key is available for the inner one-shot completion. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return (ctx.apiKeyForImages ?: '').toString().trim().length() > 0
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    Map m = new LinkedHashMap<>((Map) (input ?: [:]))
    String userPrompt = m.userPrompt?.toString()?.trim()
    if (!userPrompt) {
      userPrompt = m.prompt?.toString()?.trim()
    }
    if (!userPrompt) {
      throw new IllegalArgumentException('Missing required field: userPrompt or prompt')
    }
    String systemInstructions = m.systemInstructions?.toString()?.trim()
    if (!systemInstructions) {
      systemInstructions = m.system?.toString()?.trim()
    }
    String innerSystem = systemInstructions ?
      systemInstructions :
      'You are a writing assistant invoked as a tool inside Crafter Studio. Follow the user text exactly. Output only what was asked (plain text, Markdown, JSON, etc.) unless instructions say otherwise.'
    int maxOut = 8192
    try {
      if (m.maxOutTokens != null) {
        maxOut =
          (m.maxOutTokens instanceof Number) ? ((Number) m.maxOutTokens).intValue() : Integer.parseInt(
            m.maxOutTokens.toString().trim())
      }
    } catch (Throwable ignoredMax) {
      maxOut = 8192
    }
    maxOut = Math.min(8192, Math.max(256, maxOut))
    String modelOverride = m.model?.toString()?.trim()
    if (!modelOverride) {
      modelOverride = m.llmModel?.toString()?.trim()
    }
    String defaultModel = (ctx.textModel ?: '').toString().trim() ?: 'gpt-4o-mini'
    String modelUse = modelOverride ?: defaultModel
    int readTimeout = 180_000
    try {
      if (m.readTimeoutMs != null) {
        readTimeout =
          (m.readTimeoutMs instanceof Number) ? ((Number) m.readTimeoutMs).intValue() : Integer.parseInt(
            m.readTimeoutMs.toString().trim())
      }
    } catch (Throwable ignoredRt) {
      readTimeout = 180_000
    }
    readTimeout = Math.min(600_000, Math.max(60_000, readTimeout))
    String apiKey = (ctx.apiKeyForImages ?: '').toString().trim()
    String text = AiOrchestration.toolsLoopSimpleCompletionAssistantText(
      apiKey,
      modelUse,
      innerSystem,
      userPrompt,
      maxOut,
      readTimeout,
      'GenerateTextNoTools'
    )
    return [
      tool         : 'GenerateTextNoTools',
      assistantText: text,
      model        : modelUse,
      promptChars  : userPrompt.length()
    ]
  }
}
