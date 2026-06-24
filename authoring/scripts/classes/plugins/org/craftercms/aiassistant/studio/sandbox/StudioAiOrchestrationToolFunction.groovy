package plugins.org.craftercms.aiassistant.studio.sandbox

import java.util.function.Function
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiOrchestrationTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolProgress

/**
 * Sandbox-safe {@link Function} adapter for {@link StudioAiOrchestrationTool} (no anonymous inner classes).
 */
final class StudioAiOrchestrationToolFunction implements Function<Map, Map> {

  private final StudioAiOrchestrationTool tool
  private final StudioAiToolContext ctx
  private final String wireName

  StudioAiOrchestrationToolFunction(StudioAiOrchestrationTool tool, StudioAiToolContext ctx) {
    this.tool = tool
    this.ctx = ctx
    this.wireName = tool.wireName()
  }

  @Override
  Map apply(Map input) {
    return StudioAiToolProgress.runWithOrchestrationTool(wireName, input, ctx.toolProgressListener, tool, ctx)
  }
}
