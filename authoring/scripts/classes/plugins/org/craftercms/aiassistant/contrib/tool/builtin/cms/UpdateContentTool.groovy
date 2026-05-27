package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsGetContentTypeFormDefinition
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSupport

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * LLM "planning" tool: loads item XML and form definition, then returns instructions for the model to edit and
 * apply via {@link WriteContentTool} or form-engine JSON (no direct write in this tool).
 */
class UpdateContentTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(UpdateContentTool)

  /** Returns the Spring AI wire name {@code update_content}. */
  @Override
  String wireName() { 'update_content' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.DESC_UPDATE_CONTENT }

  /** JSON Schema for site, path, and natural-language {@code instructions}. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  /**
   * Loads content and form definition, chooses form-forward vs repository-write guidance from context flags,
   * and returns a payload the model uses on subsequent turns (not a repository mutation).
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    def instructions = input?.instructions?.toString()
    if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def contentPath = input?.contentPath?.toString()?.trim()
    if (!contentPath) throw new IllegalArgumentException('Missing required field: contentPath')
    Map gotItem = CmsGetContent.read(ctx.ops, siteId, contentPath) as Map
    def contentXml = gotItem?.contentXml?.toString() ?: ''
    def contentTypeId = StudioAiToolSupport.extractContentTypeIdFromItemXml(contentXml)
    def formDefXml = null
    def formFieldIds = []
    if (contentTypeId) {
      try {
        def formRes = CmsGetContentTypeFormDefinition.load(ctx.ops, siteId, contentTypeId)
        def raw = formRes?.formDefinitionXml?.toString()
        if (raw?.trim()) {
          formFieldIds = StudioAiToolSupport.extractFormFieldIdsFromFormDefinitionXml(raw)
          formDefXml = raw
        }
      } catch (Throwable t) {
        log.debug('update_content: form definition for {} failed: {}', contentTypeId, t.toString())
      }
    }
    boolean formForwardContent = ctx.fullSuppressRepoWrites ||
      (ctx.pathProtectFormItem && AuthoringPreviewContext.sameRepoPath(contentPath, ctx.normProtectedFormItemPath))
    def payload = [
      action        : 'update_content',
      siteId        : siteId,
      instructions  : instructions,
      promptGuidance: formForwardContent ? ToolPrompts.UPDATE_CONTENT_FORM_ENGINE : ToolPrompts.UPDATE_CONTENT,
      contentPath   : contentPath,
      contentXml    : contentXml,
      nextStep      : formForwardContent ? ToolPrompts.nextStepUpdateContentFormForward(contentPath) : ToolPrompts.nextStepUpdateContent(contentPath)
    ]
    if (contentTypeId) {
      payload.contentTypeId = contentTypeId
    }
    if (formFieldIds) {
      payload.formFieldIds = formFieldIds
    }
    if (formDefXml) {
      payload.formDefinitionForContentType = formDefXml
    }
    ['xmlWellFormed', 'xmlParseError', 'xmlRepairReminder'].each { String k ->
      if (gotItem != null && gotItem.containsKey(k)) {
        payload[k] = gotItem[k]
      }
    }
    return payload
  }
}
