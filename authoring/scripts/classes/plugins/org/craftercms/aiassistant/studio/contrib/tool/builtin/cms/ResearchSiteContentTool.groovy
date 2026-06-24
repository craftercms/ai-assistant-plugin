package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsResearchSiteContent
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * LLM tool that searches and samples site content via OpenSearch-backed research (gated by site/project config).
 */
class ResearchSiteContentTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code ResearchSiteContent}. */
  @Override
  String wireName() { 'ResearchSiteContent' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_RESEARCH_SITE_CONTENT() }

  /** JSON Schema for query, limits, and optional path prefix. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.RESEARCH_SITE_CONTENT }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /** Only registered when {@code siteContentResearchGloballyEnabled} is true on operations. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return CmsResearchSiteContent.globallyEnabled()
  }

  /**
   * Validates {@code query}, parses optional hit/fetch limits, and delegates to
   * {@link plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations#researchSiteContent}.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    String query = input?.query?.toString()?.trim() ?: input?.q?.toString()?.trim()
    if (!query) {
      throw new IllegalArgumentException('Missing required field: query')
    }
    Integer maxSearch = null
    Integer maxFetch = null
    try {
      if (input?.maxSearchHits != null) {
        maxSearch =
          (input.maxSearchHits instanceof Number) ?
            ((Number) input.maxSearchHits).intValue() :
            Integer.parseInt(input.maxSearchHits.toString().trim())
      }
    } catch (Throwable ignored) {
      maxSearch = null
    }
    try {
      if (input?.maxFetchItems != null) {
        maxFetch =
          (input.maxFetchItems instanceof Number) ?
            ((Number) input.maxFetchItems).intValue() :
            Integer.parseInt(input.maxFetchItems.toString().trim())
      }
    } catch (Throwable ignored) {
      maxFetch = null
    }
    String prefix = input?.pathPrefix?.toString()?.trim()
    return CmsResearchSiteContent.research(ctx.ops, input?.siteId as String, query, maxSearch, maxFetch, prefix) as Map
  }
}
