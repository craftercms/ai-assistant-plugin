package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.util.ContentSubgraphAggregator
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * LLM tool that walks dependent/embedded content from an anchor path (translation / dependency scope tree).
 * Wire alias {@code ListContentTranslationScope} is accepted by the recipe engine.
 */
class ListContentDependencyScopeTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code ListContentDependencyScope}. */
  @Override
  String wireName() { 'ListContentDependencyScope' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_LIST_CONTENT_DEPENDENCY_SCOPE() }

  /** JSON Schema for anchor path, site, and optional depth/item limits. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.LIST_CONTENT_DEPENDENCY_SCOPE }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /**
   * Parses optional numeric limits, then builds the scope tree via
   * {@link ContentSubgraphAggregator#buildTranslationScopeTree}.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
    Integer maxItems = parseOptionalInt(input?.maxItems)
    Integer maxDepth = parseOptionalInt(input?.maxDepth)
    Integer chunkSize = parseOptionalInt(input?.chunkSize)
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    if (!contentPath) throw new IllegalArgumentException('Missing required field: contentPath (or path)')
    return ContentSubgraphAggregator.buildTranslationScopeTree(ctx.ops, siteId, contentPath, maxItems, maxDepth, chunkSize)
  }

  /** Parses an optional integer tool argument; returns null when missing or invalid. */
  private static Integer parseOptionalInt(Object v) {
    if (v == null) return null
    try {
      return (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim())
    } catch (Throwable ignored) {
      return null
    }
  }
}
