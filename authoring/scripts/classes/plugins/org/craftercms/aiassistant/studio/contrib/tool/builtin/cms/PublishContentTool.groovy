package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsPublishContent
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * LLM tool that triggers Studio publish for paths supplied in tool input (respects form-item path protection).
 */
class PublishContentTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(PublishContentTool)

  /** Returns the Spring AI wire name {@code publish_content}. */
  @Override
  String wireName() { 'publish_content' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.DESC_PUBLISH_CONTENT }

  /** JSON Schema for publish targets and environment fields. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  /** Disabled when orchestration sets {@code fullSuppressRepoWrites} on the tool context. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !ctx.fullSuppressRepoWrites
  }

  /**
   * Calls {@code publishContentFromToolInput} with path-protection flags; on failure returns {@code ok: false}
   * instead of throwing so the model can recover.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    try {
      return CmsPublishContent.fromToolInput(
        ctx.ops,
        (Map) (input ?: [:]),
        ctx.pathProtectFormItem,
        ctx.normProtectedFormItemPath
      )
    } catch (Throwable t) {
      log.warn('publish_content failed: {}', t.message)
      return [
        action : 'publish_content',
        ok     : false,
        message: (t.message ?: t.toString())
      ]
    }
  }
}
