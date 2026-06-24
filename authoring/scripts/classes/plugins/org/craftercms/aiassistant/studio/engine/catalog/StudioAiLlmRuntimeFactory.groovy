package plugins.org.craftercms.aiassistant.studio.engine.catalog

import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.contrib.llm.vendor.anthropic.AnthropicSpringAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.contrib.llm.wire.openaispec.OpenAiSpecSpringAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.contrib.llm.script.StudioAiScriptLlmContainerRuntime

/**
 * Selects a {@link StudioAiLlmRuntime} from a normalized kind ({@link StudioAiLlmKind}).
 */
final class StudioAiLlmRuntimeFactory {

  /**
   * Private constructor; not for direct use.
   */
private StudioAiLlmRuntimeFactory() {}

  /**
   * Runs runtime for using Studio services and returns the tool payload.
   * @param normalizedKind Caller-supplied input.
   * @return StudioAiLlmRuntime result.
   */
  static StudioAiLlmRuntime runtimeFor(String normalizedKind) {
    String n = (normalizedKind ?: '').toString()
    if (StudioAiLlmKind.isScriptHostedLlm(n)) {
      return new StudioAiScriptLlmContainerRuntime(StudioAiLlmKind.scriptLlmIdFromNormalized(n))
    }
    if (StudioAiLlmKind.useToolsLoopChatRestClientBuiltInKinds(n)) {
      return OpenAiSpecSpringAiLlmRuntime.INSTANCE
    }
    if (StudioAiLlmKind.isAnthropicClaude(n)) {
      return AnthropicSpringAiLlmRuntime.INSTANCE
    }
    throw new IllegalStateException(
      "Unsupported normalized llm kind '${n}'. Expected a value produced by StudioAiLlmKind.normalize (openAI, claude, script:…, etc.)."
    )
  }
}
