package plugins.org.craftercms.aiassistant.tools.general

import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.http.HttpUrlPost
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

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
