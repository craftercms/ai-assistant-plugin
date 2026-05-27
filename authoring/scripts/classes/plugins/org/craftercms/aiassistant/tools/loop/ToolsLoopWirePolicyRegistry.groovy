package plugins.org.craftercms.aiassistant.tools.loop

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Map

/**
 * Engine catalog for tools-loop wire behavior. Built-in, site user, and MCP tools share the same lookup;
 * orchestration calls {@link #policyFor(String)} only — no per-tool {@code switch} in {@code AiOrchestration}.
 */
final class ToolsLoopWirePolicyRegistry {

  /** Utility class; no instances. */
  private ToolsLoopWirePolicyRegistry() {}

  private static final Map<String, ToolsLoopWirePolicy> POLICIES = buildPolicies()

  /** Fallback for dynamic {@code mcp_*} tool names not listed in {@link #POLICIES}. */
  private static final ToolsLoopWirePolicy MCP_DEFAULT = ToolsLoopWirePolicy.defaults()

  /**
   * Registers all built-in wire names known at compile time.
   * Site-specific user tool ids are not listed here; they use {@link ToolsLoopWirePolicy#siteUserToolPolicy}
   * only when the wire name is {@code InvokeSiteUserTool}.
   */
  private static Map<String, ToolsLoopWirePolicy> buildPolicies() {
    Map<String, ToolsLoopWirePolicy> m = new LinkedHashMap<>()

    List<String> readTools = [
      'ContentExists',
      'GetContent',
      'ListContentDependencyScope',
      'ListContentTranslationScope',
      'ListStudioContentTypes',
      'GetContentTypeFormDefinition',
      'GetContentVersionHistory',
      'FetchHttpUrl',
      'ListPagesAndComponents',
      'ResearchSiteContent',
      'WebSearch',
      'SerpApiWebSearch',
      'GenerateTextNoTools'
    ]
    for (String w : readTools) {
      m.put(w, 'FetchHttpUrl'.equals(w) ? ToolsLoopWirePolicy.fetchHttpUrlPolicy() : ToolsLoopWirePolicy.readPolicy())
    }

    m.put('QueryExpertGuidance', ToolsLoopWirePolicy.expertReadPolicy())
    m.put('GetCrafterizingPlaybook', ToolsLoopWirePolicy.expertReadPolicy())
    m.put('ConsultCrafterQ', ToolsLoopWirePolicy.expertReadPolicy())
    m.put('GetPreviewHtml', ToolsLoopWirePolicy.verificationReadPolicy())
    m.put('analyze_template', ToolsLoopWirePolicy.analysisPolicy())

    m.put('WriteContent', ToolsLoopWirePolicy.writeContentPolicy())
    m.put(
      'update_content',
      ToolsLoopWirePolicy.preparatoryUpdatePolicy(ToolsLoopWirePolicy.WIRE_COMPACT_UPDATE_CONTENT)
    )
    m.put('update_template', ToolsLoopWirePolicy.preparatoryUpdatePolicy())
    m.put('update_content_type', ToolsLoopWirePolicy.preparatoryUpdatePolicy())
    for (String w : [
      'revert_change',
      'publish_content',
      'TranslateContentItem',
      'TranslateContentBatch',
      'TransformContentSubgraph'
    ]) {
      m.put(w, ToolsLoopWirePolicy.writeMutationPolicy())
    }

    m.put('GenerateImage', ToolsLoopWirePolicy.generateImagePolicy())
    m.put('GeneratePlaceholderImage', ToolsLoopWirePolicy.placeholderImagePolicy())
    m.put('InvokeSiteUserTool', ToolsLoopWirePolicy.siteUserToolPolicy())

    return Collections.unmodifiableMap(m)
  }

  /**
   * @param wireName Chat Completions function name ({@code tool_calls[].function.name})
   * @return policy for the wire, or {@link ToolsLoopWirePolicy#defaults()} for unknown / {@code mcp_*} names
   */
  static ToolsLoopWirePolicy policyFor(String wireName) {
    String w = wireName?.toString()?.trim()
    if (!w) {
      return ToolsLoopWirePolicy.defaults()
    }
    ToolsLoopWirePolicy p = POLICIES.get(w)
    if (p != null) {
      return p
    }
    if (w.startsWith('mcp_')) {
      return MCP_DEFAULT
    }
    return ToolsLoopWirePolicy.defaults()
  }

  /**
   * Second emoji after 🛠️ on server tool-progress lines (read 🔍, write ✏️, analysis 📈, other 🔄).
   * @param wireName tool wire name
   */
  static String progressCategoryEmoji(String wireName) {
    String cat = policyFor(wireName).progressCategory
    switch (cat) {
      case ToolsLoopWirePolicy.PROGRESS_READ:
        return '🔍'
      case ToolsLoopWirePolicy.PROGRESS_WRITE:
        return '✏️'
      case ToolsLoopWirePolicy.PROGRESS_ANALYSIS:
        return '📈'
      default:
        return '🔄'
    }
  }

  /**
   * @param wireName tool wire name
   * @return true when progress lines should use the expert prefix (🛠️🤓)
   */
  static boolean isExpertGuidanceWire(String wireName) {
    return policyFor(wireName).expertGuidancePrefix
  }

  /**
   * @param wireName tool wire name
   * @return {@code main} or {@code verification} for SSE {@code pipelineStage} metadata
   */
  static String pipelineStageForWire(String wireName) {
    return policyFor(wireName).pipelineStage ?: 'main'
  }
}
