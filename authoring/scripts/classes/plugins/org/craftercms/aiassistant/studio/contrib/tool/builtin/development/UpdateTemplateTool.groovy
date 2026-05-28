package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.development

import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsGetContentTypeFormDefinition
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

/**
 * : . Contrib implementation used by the plugin runtime.
 */
class UpdateTemplateTool extends AbstractStudioAiTool {

  @Override
  String wireName() { 'update_template' }

  @Override
  String description() { ToolPrompts.DESC_UPDATE_TEMPLATE }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    def instructions = input?.instructions?.toString()
    if (!instructions?.trim()) throw new IllegalArgumentException('Missing required field: instructions')
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def templatePath = input?.templatePath?.toString()?.trim()
    def contentPath = input?.contentPath?.toString()?.trim()
    if (!templatePath && contentPath) {
      templatePath = CmsGetContent.resolveTemplatePath(ctx.ops, siteId, contentPath)
    }
    if (!templatePath) {
      throw new IllegalArgumentException('Missing required field: templatePath (or contentPath that resolves a display-template)')
    }
    def templateText = CmsGetContent.read(ctx.ops, siteId, templatePath)?.contentXml?.toString() ?: ''
    def contentType = input?.contentType?.toString()?.trim()
    def formDef = contentType ? CmsGetContentTypeFormDefinition.load(ctx.ops, siteId, contentType)?.formDefinitionXml?.toString() : null
    boolean formForwardTpl = ctx.fullSuppressRepoWrites ||
      (ctx.pathProtectFormItem && contentPath && AuthoringPreviewContext.sameRepoPath(contentPath, ctx.normProtectedFormItemPath))
    return [
      action: 'update_template',
      siteId: siteId,
      instructions: instructions,
      promptGuidance: formForwardTpl ? ToolPrompts.UPDATE_TEMPLATE_FORM_ENGINE : ToolPrompts.UPDATE_TEMPLATE,
      templatePath: templatePath,
      template: templateText,
      contentPath: contentPath,
      contentType: contentType,
      contentTypeFormDefinition: formDef,
      nextStep: formForwardTpl ? ToolPrompts.nextStepUpdateTemplateFormForward(templatePath) : ToolPrompts.nextStepUpdateTemplate(templatePath)
    ]
  }
}
