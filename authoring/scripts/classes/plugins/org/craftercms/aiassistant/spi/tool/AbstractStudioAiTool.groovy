package plugins.org.craftercms.aiassistant.spi.tool

import org.springframework.ai.tool.function.FunctionToolCallback
import plugins.org.craftercms.aiassistant.engine.catalog.AiOrchestrationTools

import java.util.function.Function

/**
 * Base class for {@link StudioAiOrchestrationTool} Groovy implementations under {@code contrib.tool.builtin}, etc.
 */
abstract class AbstractStudioAiTool implements StudioAiOrchestrationTool {

  /**
   * Default allow-all implementation; subclasses may disable per context (maintenance flags, MCP health, etc.).
   */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return true
  }

  /**
   * Defaults to {@code null} so orchestration treats this as an unbucketed built-in tool unless overridden.
   */
  @Override
  String pipelineStage() {
    return null
  }

  /**
   * {@code false} for mutating MCP/built-in tools; recipe-engine-safe tools override with {@code true}.
   */
  @Override
  boolean recipeEngineReadOnly() {
    return false
  }

  /**
   * {@code false} by default. Tools that may run under {@code phases.confirmation} {@code engineSteps}
   * override with {@code true} (e.g. {@link plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.SlackPostMessageTool}).
   */
  @Override
  boolean recipeEngineConfirmationStep() {
    return false
  }

  /** {@inheritDoc} */
  @Override
  Map applyRecipeConfirmationArgDefaults(Map resolvedArgs, String lastAssistantMarkdown) {
    return resolvedArgs instanceof Map ? resolvedArgs : [:]
  }

  /** {@inheritDoc} */
  @Override
  Map maintainerObservability(String phase, Map input, Object toolResult, Throwable err) {
    return [:]
  }

  /**
   * Builds a Spring AI {@link FunctionToolCallback} that wraps {@link #execute} identically to CMS peers.
   * Runs inside {@link AiOrchestrationTools#runWithToolProgress} so SSE listeners observe MCP+CBS timings.
   * Wires Groovy meta {@code toolCallResultConverter} because Builder lacks a public setter.
   */
  Object toFunctionToolCallback(StudioAiToolContext ctx) {
    final String name = wireName()
    final StudioAiToolContext buildCtx = ctx
    return FunctionToolCallback.builder(name, new Function<Map, Map>() {
      @Override
      Map apply(Map input) {
        return AiOrchestrationTools.runWithToolProgress(name, input, buildCtx.toolProgressListener, {
          AiOrchestrationTools.logToolInvocationPublic(name, (Map) (input ?: [:]))
          execute((Map) (input ?: [:]), buildCtx)
        })
      }
    })
      .description(description())
      .inputSchema(inputSchemaJson())
      .inputType(Map.class)
      // Spring AI FunctionToolCallback.Builder has no public toolCallResultConverter(…) step; Groovy invokeMethod wires it.
      .invokeMethod('toolCallResultConverter', ctx.converter)
      .build()
  }
}
