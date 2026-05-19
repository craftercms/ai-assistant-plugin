package plugins.org.craftercms.aiassistant.tools.mcp

import plugins.org.craftercms.aiassistant.mcp.StudioAiMcpClient
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext

/**
 * SPI adapter for a single dynamic MCP tool ({@code mcp_<serverId>_<toolName>} wire name).
 * Holds JSON-RPC connection handles plus sanitized schema/description snapshots per discovered remote tool.
 */
final class McpWireStudioAiTool extends AbstractStudioAiTool {

  private final String wire
  private final String desc
  private final String schemaJson
  private final StudioAiMcpClient.McpConnection connection
  private final String mcpToolName

  /**
   * Copies MCP discovery outputs into immutable fields used during Chat Completions registration.
   * Trims identifiers and substitutes an empty JSON-schema object when servers omit structured inputs.
   * Leaves connections untouched until {@link #execute} validates readiness.
   */
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

  /**
   * Returns stable Spring-registration name ({@code mcp_…}) for Chat Completions tool arrays.
   */
  @Override
  String wireName() { wire }

  /**
   * Surfaces MCP-declared prose description to the LLM without local overrides.
   */
  @Override
  String description() { desc }

  /**
   * Serializes MCP input schema JSON for FunctionToolCallback validation.
   */
  @Override
  String inputSchemaJson() { schemaJson }

  /**
   * Tags metrics / UI progress buckets as {@code mcp} so operators can filter MCP vs CMS traffic.
   */
  @Override
  String pipelineStage() { 'mcp' }

  /**
   * MCP tools are not allowlisted for recipe-engine prefetch today.
   */
  @Override
  boolean recipeEngineReadOnly() { false }

  /**
   * Validates the live connection then forwards argument maps to {@code tools/call}.
   * Returns Groovy maps cast from the JSON-RPC payload.
   * Throws {@link IllegalStateException} when wiring is incomplete.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    if (connection == null || !mcpToolName) {
      throw new IllegalStateException('MCP tool not connected')
    }
    return connection.toolsCall(mcpToolName, (Map) (input ?: [:])) as Map
  }

  /**
   * Builds Spring AI {@link org.springframework.ai.tool.function.FunctionToolCallback} bridging MCP RPC + SSE progress listeners.
   * Wraps execution with {@link AiOrchestrationTools#runWithToolProgress} for parity with tools.
   * Reuses ctx converters for typed result marshaling back into chat transcripts.
   */
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
