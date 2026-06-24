package plugins.org.craftercms.aiassistant.studio.contrib.llm.script

import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiLlmRuntime
import plugins.org.craftercms.aiassistant.studio.spi.llm.StudioAiRuntimeBuildRequest
import groovy.lang.Closure

/**
 * {@link StudioAiLlmRuntime} backed by a site script {@link Map} with {@code buildSessionBundle} {@link Closure}.
 */
final class StudioAiMapBackedScriptLlmRuntime implements StudioAiLlmRuntime {

  private final String normalizedKindToken
  private final boolean supportsTools
  private final Closure buildClosure

  StudioAiMapBackedScriptLlmRuntime(String normalizedKindToken, boolean supportsTools, Closure buildClosure) {
    this.normalizedKindToken = (normalizedKindToken ?: '').toString()
    this.supportsTools = supportsTools
    this.buildClosure = buildClosure
  }

  @Override
  String normalizedKind() {
    return normalizedKindToken
  }

  @Override
  /**
   * Supports native studio tools.
   * @return True when the check succeeds.
   */
  boolean supportsNativeStudioTools() {
    return supportsTools
  }

  @Override
  Map buildSessionBundle(StudioAiRuntimeBuildRequest req) {
    Object out = buildClosure.call(req)
    if (!(out instanceof Map)) {
      throw new IllegalStateException(
        "Script LLM buildSessionBundle must return a Map (Spring AI session bundle); got ${out?.class?.name}"
      )
    }
    return new LinkedHashMap<>((Map) out)
  }
}
