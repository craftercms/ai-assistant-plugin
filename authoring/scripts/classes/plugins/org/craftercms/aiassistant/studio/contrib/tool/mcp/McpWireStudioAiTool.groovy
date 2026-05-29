package plugins.org.craftercms.aiassistant.studio.contrib.tool.mcp

import plugins.org.craftercms.aiassistant.studio.contrib.tool.mcp.StudioAiMcpClient
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext

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

  @Override
  Map maintainerObservability(String phase, Map input, Object toolResult, Throwable err) {
    Map out = new LinkedHashMap(tool: wire, mcpToolName: mcpToolName)
    if (connection?.serverId) {
      out.mcpServerId = connection.serverId?.toString()?.trim()
    }
    if ('start'.equals(phase) && input instanceof Map && !input.isEmpty()) {
      out.inputKeys = input.keySet().collect { it?.toString()?.trim() }.findAll { it }.sort()
    }
    if (toolResult instanceof Map) {
      Map tr = (Map) toolResult
      if (tr.containsKey('ok')) {
        out.ok = tr.ok
      }
      String msg = tr.message?.toString()?.trim() ?: tr.error?.toString()?.trim()
      if (msg) {
        out.message = msg.length() > 300 ? msg.substring(0, 297) + '…' : msg
      }
    }
    if (err != null) {
      String em = err.message ?: err.toString()
      out.error = em.length() > 300 ? em.substring(0, 297) + '…' : em
    }
    return Collections.unmodifiableMap(out)
  }

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

}
