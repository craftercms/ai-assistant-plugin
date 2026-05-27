package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsGetContentTypeFormDefinition
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

/**
 * LLM "planning" tool: loads a content type form definition and returns guidance for editing it in Studio
 * (form-forward when repository writes are suppressed).
 */
class UpdateContentTypeTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code update_content_type}. */
  @Override
  String wireName() { 'update_content_type' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.DESC_UPDATE_CONTENT_TYPE }

  /** JSON Schema for site, content type id, and {@code instructions}. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  /**
   * Loads {@code form-definition.xml} for {@code contentType}, attaches XML well-formedness hints when present,
   * and returns next-step guidance for the model (does not write the config file itself).
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    def instructions = input?.instructions?.toString()
    if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def contentType = input?.contentType?.toString()?.trim()
    if (!contentType) throw new IllegalArgumentException('Missing required field: contentType')
    Map formRes = CmsGetContentTypeFormDefinition.load(ctx.ops, siteId, contentType) as Map
    def xml = formRes?.formDefinitionXml?.toString() ?: ''
    def cfgPath = formRes?.path?.toString()
    Map payloadCt = [
      action: 'update_content_type',
      siteId: siteId,
      instructions: instructions,
      promptGuidance: ctx.fullSuppressRepoWrites ? ToolPrompts.UPDATE_CONTENT_TYPE_FORM_ENGINE : ToolPrompts.UPDATE_CONTENT_TYPE,
      contentType: contentType,
      formDefinitionPath: cfgPath,
      formDefinitionXml: xml,
      nextStep: ctx.fullSuppressRepoWrites ? ToolPrompts.nextStepUpdateContentTypeFormForward(cfgPath) : ToolPrompts.nextStepUpdateContentType(cfgPath)
    ]
    ['xmlWellFormed', 'xmlParseError', 'xmlRepairReminder'].each { String k ->
      if (formRes != null && formRes.containsKey(k)) {
        payloadCt[k] = formRes[k]
      }
    }
    return payloadCt
  }
}
