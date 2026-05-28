package plugins.org.craftercms.aiassistant.engine.catalog

import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig
import plugins.org.craftercms.aiassistant.contrib.tool.mcp.StudioAiMcpClient
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.contrib.tool.mcp.McpWireStudioAiTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.ContentExistsTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.GenerateImageTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.GeneratePlaceholderImageTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.GetContentTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.GetContentTypeFormDefinitionTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.GetContentVersionHistoryTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.GetPreviewHtmlTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.ListContentDependencyScopeTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.ListPagesAndComponentsTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.ListStudioContentTypesTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.PublishContentTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.ResearchSiteContentTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.RevertChangeTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.TransformContentSubgraphTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.TranslateContentBatchTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.TranslateContentItemTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.UpdateContentTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.UpdateContentTypeTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.WriteContentTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.general.GenerateTextNoToolsTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.general.QueryExpertGuidanceTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.development.AnalyzeTemplateTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.development.GetCrafterizingPlaybookTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.development.UpdateTemplateTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.FetchHttpUrlTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.PostHttpUrlTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.SlackPostMessageTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.ConsultCrafterQTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.SerpApiWebSearchTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.WebSearchTool
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.site.InvokeSiteUserTool
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.List
import java.util.Map
import java.util.Set

/**
 * Composes all shipped {@link plugins.org.craftercms.aiassistant.spi.tool.StudioAiOrchestrationTool} classes into Spring AI
 * {@code FunctionToolCallback} entries for a request ({@link #CORE_TOOLS}).
 * <p>Includes CMS, integrations, development, {@code general} ({@code GenerateTextNoTools}, {@code QueryExpertGuidance}),
 * translate/image tools, and {@code InvokeSiteUserTool}. MCP wire tools are added separately via
 * {@link #buildMcpToolCallbacks}. Catalog assembly and site policy filters live on
 * {@link plugins.org.craftercms.aiassistant.engine.catalog.AiOrchestrationTools#build}.</p>
 */
final class StudioAiToolRegistry {

  private static final Logger log = LoggerFactory.getLogger(StudioAiToolRegistry.class)

  /**
   * Private constructor; not for direct use.
   */
private StudioAiToolRegistry() {}

  private static final List<AbstractStudioAiTool> CORE_TOOLS = Collections.unmodifiableList([
    new ContentExistsTool(),
    new GetContentTool(),
    new ListContentDependencyScopeTool(),
    new ListStudioContentTypesTool(),
    new GetContentTypeFormDefinitionTool(),
    new GetContentVersionHistoryTool(),
    new GetPreviewHtmlTool(),
    new FetchHttpUrlTool(),
    new PostHttpUrlTool(),
    new WebSearchTool(),
    new SerpApiWebSearchTool(),
    new ConsultCrafterQTool(),
    new SlackPostMessageTool(),
    new ResearchSiteContentTool(),
    new QueryExpertGuidanceTool(),
    new GeneratePlaceholderImageTool(),
    new GenerateImageTool(),
    new GenerateTextNoToolsTool(),
    new TranslateContentItemTool(),
    new TranslateContentBatchTool(),
    new TransformContentSubgraphTool(),
    new WriteContentTool(),
    new ListPagesAndComponentsTool(),
    new UpdateTemplateTool(),
    new UpdateContentTool(),
    new UpdateContentTypeTool(),
    new AnalyzeTemplateTool(),
    new PublishContentTool(),
    new GetCrafterizingPlaybookTool(),
    new RevertChangeTool(),
    new InvokeSiteUserTool(),
  ])

  private static final Map<String, AbstractStudioAiTool> CORE_BY_WIRE_NAME = buildCoreByWireName()

  private static Map<String, AbstractStudioAiTool> buildCoreByWireName() {
    Map<String, AbstractStudioAiTool> built = new LinkedHashMap<>()
    for (AbstractStudioAiTool tool : CORE_TOOLS) {
      built.put(tool.wireName(), tool)
    }
    return Collections.unmodifiableMap(built)
  }

  /** Returns the immutable list of built-in / general orchestration tool instances. */
  static List<AbstractStudioAiTool> coreTools() {
    return CORE_TOOLS
  }

  /** Wire-name → tool instance map for dispatch and prefetch. */
  static Map<String, AbstractStudioAiTool> coreToolsByWireName() {
    return CORE_BY_WIRE_NAME
  }

  /**
   * Wire names allowed for {@link plugins.org.craftercms.aiassistant.engine.routing.subrouting.AuthoringIntentRecipeEngine} prefetch.
   * Includes legacy {@code ListContentTranslationScope} alias.
   */
  static Set<String> recipeEngineReadOnlyWireNames() {
    Set<String> names = new LinkedHashSet<>()
    names.add('ListContentTranslationScope')
    for (AbstractStudioAiTool tool : CORE_TOOLS) {
      if (tool.recipeEngineReadOnly()) {
        names.add(tool.wireName())
      }
    }
    return Collections.unmodifiableSet(names)
  }

  /**
   * Executes a read-only core tool for recipe prefetch (no Spring {@code FunctionToolCallback}).
   */
  static Map executeRecipePrefetchTool(String toolName, Map input, StudioToolOperations ops) {
    String wire = toolName?.toString()?.trim()
    if (wire == 'ListContentTranslationScope') {
      wire = 'ListContentDependencyScope'
    }
    AbstractStudioAiTool tool = coreToolsByWireName().get(wire)
    if (tool == null || !tool.recipeEngineReadOnly()) {
      throw new IllegalArgumentException('Unsupported tool: ' + toolName)
    }
    StudioAiToolContext ctx = StudioAiToolContext.forRecipeEngine(ops)
    return tool.execute((Map) (input ?: [:]), ctx) as Map
  }

  /**
   * Wire names allowed for {@link plugins.org.craftercms.aiassistant.engine.routing.subrouting.AuthoringIntentRecipeEngine}
   * confirmation-phase steps (after Action chat work).
   */
  static Set<String> recipeEngineConfirmationWireNames() {
    Set<String> names = new LinkedHashSet<>()
    for (AbstractStudioAiTool tool : CORE_TOOLS) {
      if (tool.recipeEngineConfirmationStep()) {
        names.add(tool.wireName())
      }
    }
    return Collections.unmodifiableSet(names)
  }

  /**
   * Lets the registered confirmation tool fill empty step {@code args} from Action-phase assistant prose.
   */
  static Map mergeRecipeConfirmationArgs(String toolName, Map resolvedArgs, String lastAssistantMarkdown) {
    String wire = toolName?.toString()?.trim()
    AbstractStudioAiTool tool = coreToolsByWireName().get(wire)
    if (tool == null || !tool.recipeEngineConfirmationStep()) {
      return resolvedArgs instanceof Map ? resolvedArgs : [:]
    }
    return tool.applyRecipeConfirmationArgDefaults(
      resolvedArgs instanceof Map ? resolvedArgs : [:],
      (lastAssistantMarkdown ?: '').toString()
    )
  }

  /** Executes a confirmation-phase recipe {@code engineSteps} tool on the Studio JVM. */
  static Map executeRecipeConfirmationTool(String toolName, Map input, StudioToolOperations ops) {
    String wire = toolName?.toString()?.trim()
    AbstractStudioAiTool tool = coreToolsByWireName().get(wire)
    if (tool == null || !tool.recipeEngineConfirmationStep()) {
      throw new IllegalArgumentException('Unsupported confirmation tool: ' + toolName)
    }
    StudioAiToolContext ctx = StudioAiToolContext.forRecipeEngine(ops)
    return tool.execute((Map) (input ?: [:]), ctx) as Map
  }

  /**
   * Builds Spring AI {@code FunctionToolCallback} entries for each enabled core tool in the registry
   * for the current orchestration context.
   */
  static List buildCoreToolCallbacks(StudioAiToolContext ctx) {
    List out = []
    for (AbstractStudioAiTool tool : CORE_TOOLS) {
      if (tool.enabled(ctx)) {
        out.add(tool.toFunctionToolCallback(ctx))
      }
    }
    return out
  }

  /**
   * Registers dynamic {@code mcp_*} tools when site {@code tools.json} enables MCP.
   */
  static List buildMcpToolCallbacks(StudioAiToolContext ctx, StudioToolOperations ops) {
    List out = []
    if (ctx == null || ops == null) {
      return out
    }
    Map aiProjectToolCfg = ctx.aiProjectToolCfg
    if (!StudioAiAssistantProjectConfig.mcpClientEnabled(aiProjectToolCfg)) {
      return out
    }
    Set<String> disabledMcpLower = StudioAiAssistantProjectConfig.disabledMcpToolsLower(aiProjectToolCfg)
    List<Map> mcpSpecs = StudioAiAssistantProjectConfig.mcpServers(aiProjectToolCfg)
    for (Map mcpSpec : mcpSpecs) {
      String serverId = mcpSpec?.id?.toString()?.trim()
      if (!serverId) {
        log.warn('MCP: skipping server entry without id')
        continue
      }
      StudioAiMcpClient.McpConnection mcpConn
      List<Map> mcpDefs
      try {
        def opened = StudioAiMcpClient.openSessionAndListTools(ops, mcpSpec)
        mcpConn = (StudioAiMcpClient.McpConnection) opened.connection
        mcpDefs = (List<Map>) opened.tools
      } catch (Throwable tm) {
        log.warn('MCP server {} not available: {}', serverId, tm.message ?: tm.toString())
        continue
      }
      for (Map tdef : mcpDefs) {
        String mcpNm = tdef?.name?.toString()?.trim()
        if (!mcpNm) {
          continue
        }
        String wname = StudioAiMcpClient.wireToolName(serverId, mcpNm)
        if (StudioAiAssistantProjectConfig.isMcpWireToolDisabled(disabledMcpLower, wname)) {
          continue
        }
        Object isch = tdef.get('inputSchema')
        String schemaJson
        if (isch instanceof CharSequence && isch.toString().trim()) {
          schemaJson = isch.toString().trim()
        } else {
          Map schema =
            isch instanceof Map ? new LinkedHashMap<>((Map) isch) : [type: 'object', properties: [:]]
          if (!schema.containsKey('type')) {
            schema = new LinkedHashMap<>(schema)
            schema.put('type', 'object')
          }
          schemaJson = JsonOutput.toJson(schema)
        }
        String desc = tdef.get('description')?.toString()?.trim()
        if (!desc) {
          desc =
            "MCP tool '${mcpNm}' on server '${serverId}' (remote). Use CMS repository tools for /site reads and writes."
        }
        if (desc.length() > 8000) {
          desc = desc.substring(0, 7997) + '…'
        }
        out.add(new McpWireStudioAiTool(wname, desc, schemaJson, mcpConn, mcpNm).toFunctionToolCallback(ctx))
      }
    }
    return out
  }
}
