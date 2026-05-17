package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PublishContentTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(PublishContentTool)

  @Override
  String wireName() { 'publish_content' }

  @Override
  String description() { ToolPrompts.DESC_PUBLISH_CONTENT }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !ctx.fullSuppressRepoWrites
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    try {
      return ctx.ops.publishContentFromToolInput(
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
