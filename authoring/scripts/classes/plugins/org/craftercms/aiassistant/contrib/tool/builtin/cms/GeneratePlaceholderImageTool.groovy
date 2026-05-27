package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsStudioPlaceholderImage
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

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
