package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsPreviewHtmlFetch
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

/**
 * LLM tool that fetches rendered preview HTML for an absolute preview URL (verification / visual check stage).
 */
class GetPreviewHtmlTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code GetPreviewHtml}. */
  @Override
  String wireName() { 'GetPreviewHtml' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.getDESC_GET_PREVIEW_HTML() }

  /** JSON Schema for preview URL, token, and optional {@code siteId}. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.GET_PREVIEW_HTML }

  /** Runs in the verification stage of the tools pipeline (after writes). */
  @Override
  String pipelineStage() { 'verification' }

  /** Permitted during recipe-engine prefetch (read-only). */
  @Override
  boolean recipeEngineReadOnly() { true }

  /**
   * Requires {@code url} or {@code previewUrl}, then delegates to {@code fetchPreviewRenderedHtml} with optional
   * preview token and site id.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def m = (Map) (input ?: [:])
    def abs = m.url?.toString()?.trim() ?: m.previewUrl?.toString()?.trim()
    if (!abs) {
      throw new IllegalArgumentException('Missing required field: url (absolute preview http(s) URL, or previewUrl alias)')
    }
    def tok = m.previewToken?.toString()?.trim()
    def sid = m.siteId?.toString()?.trim()
    return CmsPreviewHtmlFetch.fetch(ctx.ops, abs, tok, sid) as Map
  }
}
