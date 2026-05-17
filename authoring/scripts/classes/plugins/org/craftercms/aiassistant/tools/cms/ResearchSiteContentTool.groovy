package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class ResearchSiteContentTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'ResearchSiteContent' }

  @Override
  String description() { ToolPrompts.getDESC_RESEARCH_SITE_CONTENT() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.RESEARCH_SITE_CONTENT }

  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return ctx?.ops?.siteContentResearchGloballyEnabled()
  }

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
    return ctx.ops.researchSiteContent(input?.siteId as String, query, maxSearch, maxFetch, prefix) as Map
  }
}
