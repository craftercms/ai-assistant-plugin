package plugins.org.craftercms.aiassistant.tools.mcp

import plugins.org.craftercms.aiassistant.mcp.StudioAiMcpClient
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext

/**
 * SPI adapter for a single dynamic MCP tool ({@code mcp_<serverId>_<toolName>} wire name).
 */
final class McpWireStudioAiTool extends AbstractStudioAiTool {

  private final String wire
  private final String desc
  private final String schemaJson
  private final StudioAiMcpClient.McpConnection connection
  private final String mcpToolName

  McpWireStudioAiTool(
    String wireName,
    String description,
    String inputSchemaJson,
    StudioAiMcpClient.McpConnection connection,
    String mcpToolName
  ) {
    this.wire = wireName?.trim() ?: ''
    this.desc = description ?: ''
    this.schemaJson = inputSchemaJson ?: '{"type":"object","properties":{}}'
    this.connection = connection
    this.mcpToolName = mcpToolName?.trim() ?: ''
  }

  @Override
  String wireName() { wire }

  @Override
  String description() { desc }

  @Override
  String inputSchemaJson() { schemaJson }

  @Override
  String pipelineStage() { 'mcp' }

  @Override
  boolean recipeEngineReadOnly() { false }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    if (connection == null || !mcpToolName) {
      throw new IllegalStateException('MCP tool not connected')
    }
    return connection.toolsCall(mcpToolName, (Map) (input ?: [:])) as Map
  }

  @Override
  Object toFunctionToolCallback(StudioAiToolContext ctx) {
    final String name = wireName()
    final StudioAiToolContext buildCtx = ctx
    return org.springframework.ai.tool.function.FunctionToolCallback.builder(name, new java.util.function.Function<Map, Map>() {
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
      .invokeMethod('toolCallResultConverter', ctx.converter)
      .build()
  }
}
