package plugins.org.craftercms.aiassistant.studio.sandbox

import org.springframework.ai.tool.function.FunctionToolCallback
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiOrchestrationTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext

/**
 * Builds Spring AI {@link FunctionToolCallback} instances without explicit {@code GroovyObject.invokeMethod}
 * (blocked by Studio Groovy sandbox blacklist). Uses normal Groovy method dispatch on the Java builder.
 */
final class StudioAiToolCallbackSupport {

  private StudioAiToolCallbackSupport() {}

  /**
   * @param builder return value of {@link FunctionToolCallback#builder(String, java.util.function.Function)}
   * @param converter {@link plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext#converter}
   */
  static Object build(FunctionToolCallback.Builder builder, Object converter) {
    if (builder == null) {
      throw new IllegalArgumentException('builder is required')
    }
    return builder.toolCallResultConverter(converter).build()
  }

  /**
   * Builds a {@link FunctionToolCallback} for a {@link StudioAiOrchestrationTool} without anonymous {@code Function} classes.
   */
  static Object buildForOrchestrationTool(
    StudioAiOrchestrationTool tool,
    StudioAiToolContext ctx,
    String description,
    String inputSchemaJson
  ) {
    String name = tool.wireName()
    return build(
      FunctionToolCallback.builder(name, new StudioAiOrchestrationToolFunction(tool, ctx))
        .description(description ?: '')
        .inputSchema(inputSchemaJson ?: '{"type":"object","properties":{}}')
        .inputType(Map.class),
      ctx.converter
    )
  }
}
