package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsContentExists
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSupport

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
  /**
   * Recipe engine read only.
   * @return True when the check succeeds.
   */
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
