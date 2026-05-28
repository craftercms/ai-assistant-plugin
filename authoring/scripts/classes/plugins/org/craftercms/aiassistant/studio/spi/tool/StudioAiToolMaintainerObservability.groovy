package plugins.org.craftercms.aiassistant.studio.spi.tool

import plugins.org.craftercms.aiassistant.studio.engine.catalog.StudioAiToolRegistry

/**
 * Optional maintainer-facing detail on tool-progress SSE rows (session debug log TIMELINE).
 * Gated by JVM flag {@value #SYSPROP_ENABLED} (default {@code true}).
 */
final class StudioAiToolMaintainerObservability {

  static final String SYSPROP_ENABLED = 'aiassistant.maintainerToolObservability'

  /**
   * Private constructor; not for direct use.
   */
private StudioAiToolMaintainerObservability() {}

  /**
   * Enabled.
   * @return True when the check succeeds.
   */
  static boolean enabled() {
    String raw = System.getProperty(SYSPROP_ENABLED, 'true')?.toString()?.trim()
    return !'false'.equalsIgnoreCase(raw) && !'0'.equals(raw)
  }

  /**
   * @param phase {@code start}, {@code done}, {@code warn}, or {@code error}
   * @return map merged into SSE {@code metadata.maintainerObservability} (may be empty)
   */
  static Map collect(String toolName, String phase, Map input, Object toolResult, Throwable err) {
    if (!enabled()) {
      return Collections.emptyMap()
    }
    String wire = toolName?.toString()?.trim()
    if (!wire) {
      return Collections.emptyMap()
    }
    Map fromTool = [:]
    try {
      def tool = StudioAiToolRegistry.coreToolsByWireName().get(wire)
      if (tool instanceof StudioAiOrchestrationTool) {
        fromTool = ((StudioAiOrchestrationTool) tool).maintainerObservability(
          phase?.toString()?.trim() ?: '',
          input instanceof Map ? (Map) input : [:],
          toolResult,
          err
        )
      }
    } catch (Throwable ignored) {
      fromTool = [:]
    }
    if (fromTool instanceof Map && !fromTool.isEmpty()) {
      return Collections.unmodifiableMap(new LinkedHashMap<>(fromTool))
    }
    return genericFallback(wire, phase, input, toolResult, err)
  }

  /**
   * Generic fallback.
   * @param wire Caller-supplied input.
   * @param phase Caller-supplied input.
   * @param input Caller-supplied input.
   * @param toolResult Caller-supplied input.
   * @param err Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map genericFallback(String wire, String phase, Map input, Object toolResult, Throwable err) {
    if ('start'.equals(phase)) {
      Map keys = [:]
      if (input instanceof Map && !input.isEmpty()) {
        keys.inputKeys = input.keySet().collect { it?.toString()?.trim() }.findAll { it }.sort()
      }
      return keys.isEmpty() ? [:] : Collections.unmodifiableMap(keys)
    }
    Map out = [tool: wire, phase: phase]
    if (toolResult instanceof Map) {
      Map tr = (Map) toolResult
      if (tr.containsKey('ok')) {
        out.ok = tr.ok
      }
      String msg = tr.message?.toString()?.trim()
      if (msg) {
        out.message = msg.length() > 500 ? msg.substring(0, 497) + '…' : msg
      }
    }
    if (err != null) {
      String em = err.message ?: err.toString()
      out.error = em.length() > 500 ? em.substring(0, 497) + '…' : em
    }
    return Collections.unmodifiableMap(out)
  }
}
