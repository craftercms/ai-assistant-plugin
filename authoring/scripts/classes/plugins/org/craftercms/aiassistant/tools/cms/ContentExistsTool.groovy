package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsContentExists
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

/**
 * LLM tool that checks whether a repository path already exists (Studio {@code contentExists})
 * before calling {@link GetContentTool} or {@link WriteContentTool}.
 */
class ContentExistsTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'ContentExists' }

  @Override
  String description() { ToolPrompts.getDESC_CONTENT_EXISTS() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CONTENT_EXISTS }

  @Override
  boolean recipeEngineReadOnly() { true }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    String siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: '')
    String path = StudioAiToolSupport.repoPathFromToolInput(input)
    List<String> paths = []
    Object pathsObj = input?.paths ?: input?.contentPaths
    if (pathsObj instanceof List) {
      for (Object p : (List) pathsObj) {
        String s = (p ?: '').toString().trim()
        if (s) {
          paths.add(s)
        }
      }
    } else if (pathsObj instanceof String && pathsObj.trim()) {
      paths.add(pathsObj.trim())
    }
    return CmsContentExists.probe(ctx.ops, siteId, path, paths) as Map
  }
}
