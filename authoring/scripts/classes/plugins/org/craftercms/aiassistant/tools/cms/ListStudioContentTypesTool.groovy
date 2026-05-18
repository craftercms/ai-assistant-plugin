package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

/**
 * LLM tool that lists content types registered in Studio for a site (optionally filtered by searchable flag or path).
 */
class ListStudioContentTypesTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code ListStudioContentTypes}. */
  @Override
  String wireName() { 'ListStudioContentTypes' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_LIST_STUDIO_CONTENT_TYPES() }

  /** JSON Schema for {@code siteId}, optional {@code searchable}, and optional path filter. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.LIST_STUDIO_CONTENT_TYPES }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /**
   * Resolves site id, interprets {@code searchable}, and returns the catalog from
   * {@link plugins.org.craftercms.aiassistant.tools.StudioToolOperations#listStudioContentTypes}.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim())
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    boolean searchable = AuthoringPreviewContext.isTruthy(input?.searchable)
    def contentPath = input?.contentPath?.toString()?.trim() ?: input?.path?.toString()?.trim()
    return ctx.ops.listStudioContentTypes(siteId, searchable, contentPath) as Map
  }
}
