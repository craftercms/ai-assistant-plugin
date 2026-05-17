package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RevertChangeTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(RevertChangeTool)

  @Override
  String wireName() { 'revert_change' }

  @Override
  String description() { ToolPrompts.DESC_REVERT_CHANGE }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !ctx.fullSuppressRepoWrites
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: (input?.site_id?.toString()?.trim()))
    if (!siteId) throw new IllegalArgumentException('Missing required field: siteId')
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    if (ctx.pathProtectFormItem && AuthoringPreviewContext.sameRepoPath(path, ctx.normProtectedFormItemPath)) {
      return [
        ok: false,
        blockedForFormClientApply: true,
        path: AuthoringPreviewContext.normalizeRepoPath(path),
        message:
          'revert_change blocked for the form item path (client-side apply). Revert from Studio if needed.',
        action: 'revert_change'
      ]
    }
    boolean revertToPrevious = AuthoringPreviewContext.isTruthy(input?.revertToPrevious)
    boolean revertToInitial = AuthoringPreviewContext.isTruthy(input?.revertToInitial) ||
      AuthoringPreviewContext.isTruthy(input?.revertToOldest) ||
      AuthoringPreviewContext.isTruthy(input?.revertToFirst)
    Map sel = ctx.ops.resolveRevertChangeVersionSelection(siteId, path, input ?: [:])
    String versionToUse = sel.version?.toString()?.trim()
    String err = null
    try {
      ctx.ops.revertContentItem(siteId, path, versionToUse, false, 'revert_change tool')
    } catch (Throwable t) {
      err = (t.message ?: t.toString())
      log.warn('revert_change failed: {}', err)
    }
    return [
      action           : 'revert_change',
      siteId           : siteId,
      path             : path,
      version          : versionToUse,
      revertToPrevious : revertToPrevious,
      revertToInitial  : revertToInitial,
      versionSelection : sel.selection?.toString() ?: '',
      ok               : err == null,
      message          : err ?: 'Reverted to selected Studio version.',
      result           : err == null ? 'ok' : null
    ]
  }
}
