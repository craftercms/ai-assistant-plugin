package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations

import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.HttpUrlPost
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * : . Contrib implementation used by the plugin runtime.
 */
class PostHttpUrlTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'PostHttpUrl' }

  @Override
  String description() { ToolPrompts.getDESC_POST_HTTP_URL() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.POST_HTTP_URL }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def url = input?.url?.toString()?.trim()
    if (!url) {
      throw new IllegalArgumentException('Missing required field: url')
    }
    String postType = input?.postType?.toString()?.trim()
    if (!postType && input?.post_type != null) {
      postType = input.post_type.toString().trim()
    }
    Object payload = input?.payload
    if (payload == null && input?.body != null) {
      payload = input.body
    }
    Map headers = null
    if (input?.headers instanceof Map) {
      headers = (Map) input.headers
    }
    Integer maxChars = null
    if (input?.maxChars != null) {
      try {
        maxChars = (input.maxChars instanceof Number)
          ? ((Number) input.maxChars).intValue()
          : Integer.parseInt(input.maxChars.toString().trim())
      } catch (Throwable ignored) {
        maxChars = null
      }
    }
    return HttpUrlPost.post(url, postType, payload, headers, maxChars) as Map
  }
}
