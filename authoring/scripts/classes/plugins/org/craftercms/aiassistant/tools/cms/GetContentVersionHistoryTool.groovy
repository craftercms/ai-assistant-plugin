package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsContentVersionHistory
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

/**
 * LLM tool that lists Studio/git versions for a repository path so the model can pick a target for
 * {@link RevertChangeTool} or compare history snippets.
 */
class GetContentVersionHistoryTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code GetContentVersionHistory}. */
  @Override
  String wireName() { 'GetContentVersionHistory' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_GET_CONTENT_VERSION_HISTORY() }

  /** JSON Schema for {@code siteId} and repository path fields. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_CONTENT_VERSION_HISTORY }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /**
   * Resolves site and path, loads version rows via {@code getContentVersionHistory}, and returns them under
   * {@code versions} in the tool result map.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    def versions = CmsContentVersionHistory.list(ctx.ops, siteId, path)
    return [
      action  : 'get_content_version_history',
      siteId  : siteId,
      path    : path,
      versions: versions
    ]
  }
}
