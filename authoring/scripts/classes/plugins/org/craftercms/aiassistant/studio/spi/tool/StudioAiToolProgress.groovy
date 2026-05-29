package plugins.org.craftercms.aiassistant.studio.spi.tool

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.engine.catalog.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.studio.engine.turn.AiOrchestration
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxClock

import java.util.concurrent.Callable

/**
 * Optional SSE progress wrapper around native tool execution.
 */
final class StudioAiToolProgress {

  private static final Logger log = LoggerFactory.getLogger(StudioAiToolProgress)

  /**
   * Private constructor; not for direct use.
   */
private StudioAiToolProgress() {}

  /**
   * {@code listener} signature: {@code (toolName, phase, inputMap, errorOrNull, toolResultOrNull, elapsedMsOrNull)} —
   * {@code phase} is {@code start}, {@code done}, {@code warn}, or {@code error}.
   */
  static Map runWithToolProgress(String toolName, Map rawInput, Closure listener, Closure work) {
    return runWithToolProgressCallable(toolName, rawInput, listener, new ClosureToolWork(work))
  }

  /**
   * Same as {@link #runWithToolProgress} but invokes {@link StudioAiOrchestrationTool#execute} directly (no nested Groovy closure).
   * Used by sandbox-safe {@link plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiOrchestrationToolFunction}.
   */
  static Map runWithOrchestrationTool(
    String toolName,
    Map rawInput,
    Closure listener,
    StudioAiOrchestrationTool tool,
    StudioAiToolContext ctx
  ) {
    Map input = (rawInput != null) ? rawInput : [:]
    return runWithToolProgressCallable(
      toolName,
      input,
      listener,
      new OrchestrationToolWork(tool, ctx, toolName, input)
    )
  }

  private static Map runWithToolProgressCallable(
    String toolName,
    Map rawInput,
    Closure listener,
    Callable<Map> work
  ) {
    Map input = (rawInput != null) ? rawInput : [:]
    if (AiOrchestration.aiAssistantPipelineCancelEffective()) {
      AiOrchestration.aiAssistantToolWorkerDiagPhase("tool_skipped_pipeline_cancelled name=${toolName}")
      log.warn(
        'AI Assistant tool skipped (author Stop / SSE disconnect / pipeline cancel or worker interrupt): tool={}',
        toolName
      )
      return [
        ok       : false,
        error    : true,
        cancelled: true,
        message  : 'Request was stopped; this tool call was not executed (no repository or side-effect work performed).',
        tool     : toolName
      ] as Map
    }
    long t0 = StudioAiSandboxClock.millis()
    if (listener) {
      try {
        listener.call(toolName, 'start', input, null, null, null)
      } catch (Throwable ignored) {
      }
    }
    try {
      Map result = work.call()
      long elapsedMs = StudioAiSandboxClock.elapsedMs(t0)
      if (listener) {
        try {
          String phase = isToolResultWarning(result) ? 'warn' : 'done'
          listener.call(toolName, phase, input, null, result, elapsedMs)
        } catch (Throwable ignored2) {
        }
      }
      if (!(result instanceof Map)) {
        throw new IllegalStateException(
          "Tool ${toolName} returned non-Map result: ${result?.getClass()?.name ?: 'null'}"
        )
      }
      return result
    } catch (Throwable t) {
      long elapsedMs = StudioAiSandboxClock.elapsedMs(t0)
      if (listener) {
        try {
          listener.call(toolName, 'error', input, t, null, elapsedMs)
        } catch (Throwable ignored3) {
        }
      }
      throw t
    }
  }

  private static final class ClosureToolWork implements Callable<Map> {
    private final Closure work

    ClosureToolWork(Closure work) {
      this.work = work
    }

    @Override
    Map call() {
      return (Map) work.call()
    }
  }

  private static final class OrchestrationToolWork implements Callable<Map> {
    private final StudioAiOrchestrationTool tool
    private final StudioAiToolContext ctx
    private final String toolName
    private final Map input

    OrchestrationToolWork(StudioAiOrchestrationTool tool, StudioAiToolContext ctx, String toolName, Map input) {
      this.tool = tool
      this.ctx = ctx
      this.toolName = toolName
      this.input = input
    }

    @Override
    Map call() {
      AiOrchestrationTools.logToolInvocationPublic(toolName, input)
      return tool.execute(input, ctx)
    }
  }

  /**
   * True when tool result warning.
   * @param result Mutable map receiving tool diagnostics or output fields.
   * @return True when the check succeeds.
   */
  private static boolean isToolResultWarning(Object result) {
    if (!(result instanceof Map)) {
      return false
    }
    Map m = (Map) result
    if (Boolean.TRUE.equals(m.error) || 'true'.equalsIgnoreCase(m.error?.toString())) {
      return true
    }
    if (m.skippedReason) {
      return true
    }
    if (m.containsKey('ok')) {
      def ok = m.ok
      if (ok instanceof Boolean && !((Boolean) ok)) {
        return true
      }
      if (ok != null && 'false'.equalsIgnoreCase(ok.toString())) {
        return true
      }
    }
    return false
  }
}
