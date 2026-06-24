package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSupport

/**
 * LLM tool that loads a repository content item as {@code contentXml} (optional git commit ref) for reads
 * and as input to {@link WriteContentTool}.
 */
class GetContentTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code GetContent}. */
  @Override
  String wireName() { 'GetContent' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_GET_CONTENT() }

  /** JSON Schema for {@code path}, {@code siteId}, and optional commit reference fields. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_CONTENT }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /**
   * Normalizes {@code path} / {@code contentPath}, resolves the effective site id, and delegates to
   * {@link plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations#getContent}.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def commitRef = input?.commitId?.toString()?.trim() ?: input?.commitRef?.toString()?.trim()
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) {
      throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    }
    String siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: '')
    return CmsGetContent.read(ctx.ops, siteId, path, commitRef) as Map
  }
}
