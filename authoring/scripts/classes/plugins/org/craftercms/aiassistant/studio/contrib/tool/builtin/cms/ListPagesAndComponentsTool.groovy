package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsListPagesAndComponents
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * LLM tool that returns a bounded list of page and component repository paths for site discovery.
 */
class ListPagesAndComponentsTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code ListPagesAndComponents}. */
  @Override
  String wireName() { 'ListPagesAndComponents' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_LIST_PAGES_AND_COMPONENTS() }

  /** JSON Schema for optional {@code siteId} and result {@code size}. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.LIST_PAGES }

  /**
   * Delegates to {@link plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations#listPagesAndComponents}
   * with default size 1000 when omitted.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    return CmsListPagesAndComponents.list(ctx.ops, input?.siteId as String, (input?.size as Integer) ?: 1000) as Map
  }
}
