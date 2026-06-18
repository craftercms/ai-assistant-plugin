package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsFindContentVersion
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSupport

/**
 * Read-only search across Studio version history (text, field value, or image path criteria).
 */
class FindContentVersionTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'FindContentVersion' }

  @Override
  String description() { ToolPrompts.getDESC_FIND_CONTENT_VERSION() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.FIND_CONTENT_VERSION }

  @Override
  boolean recipeEngineReadOnly() { true }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    return CmsFindContentVersion.find(ctx.ops, siteId, path, input ?: [:]) as Map
  }
}
