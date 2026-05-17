package plugins.org.craftercms.aiassistant.tools.general

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

class WebSearchTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'WebSearch' }

  @Override
  String description() { ToolPrompts.getDESC_WEB_SEARCH() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.WEB_SEARCH }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    String query = input?.query?.toString()?.trim()
    if (!query) {
      query = input?.q?.toString()?.trim()
    }
    if (!query) {
      throw new IllegalArgumentException('Missing required field: query')
    }
    Integer maxResults = null
    if (input?.maxResults != null) {
      try {
        maxResults =
          (input.maxResults instanceof Number) ?
            ((Number) input.maxResults).intValue() :
            Integer.parseInt(input.maxResults.toString().trim())
      } catch (Throwable ignored) {
        maxResults = null
      }
    }
    return ctx.ops.webSearch(query, maxResults) as Map
  }
}
