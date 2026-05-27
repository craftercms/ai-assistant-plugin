package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsStudioPlaceholderImage
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

/**
 * Returns a Studio / XB-style {@code data:image/png;base64,...} sample placeholder for required image-picker fields
 * when the author did not ask for specific generated art (use {@code GenerateImage} for that).
 */
class GeneratePlaceholderImageTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'GeneratePlaceholderImage' }

  @Override
  String description() { ToolPrompts.getDESC_GENERATE_PLACEHOLDER_IMAGE() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GENERATE_PLACEHOLDER_IMAGE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    return CmsStudioPlaceholderImage.generate((Map) (input ?: [:])) as Map
  }
}
