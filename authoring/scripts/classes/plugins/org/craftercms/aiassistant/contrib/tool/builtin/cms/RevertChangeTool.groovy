package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms

import plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsRevertChange
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSupport

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * LLM tool that restores a repository item to a prior Studio/git version using flags such as
 * {@code revertToPrevious}, explicit {@code version}, or {@code contentContains} resolved server-side.
 */
class RevertChangeTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(RevertChangeTool)

  /** Returns the Spring AI wire name {@code revert_change}. */
  @Override
  String wireName() { 'revert_change' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.DESC_REVERT_CHANGE }

  /** JSON Schema for path, site, and version-selection arguments. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.CMS_LOOSE }

  /** Disabled when orchestration sets {@code fullSuppressRepoWrites} on the tool context. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !ctx.fullSuppressRepoWrites
  }

  /**
   * Blocks revert on the protected form item path, resolves the target version via
   * {@link plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations#resolveRevertChangeVersionSelection},
   * then calls {@code revertContentItem} and returns a structured ok/message payload.
   */
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
    // Echo flags for the model; version selection is delegated to resolveRevertChangeVersionSelection.
    boolean revertToPrevious = AuthoringPreviewContext.isTruthy(input?.revertToPrevious)
    boolean revertToInitial = AuthoringPreviewContext.isTruthy(input?.revertToInitial) ||
      AuthoringPreviewContext.isTruthy(input?.revertToOldest) ||
      AuthoringPreviewContext.isTruthy(input?.revertToFirst)
    Map sel = CmsRevertChange.resolveVersionSelection(ctx.ops, siteId, path, input ?: [:])
    String versionToUse = sel.version?.toString()?.trim()
    String err = null
    try {
      CmsRevertChange.revertItem(ctx.ops, siteId, path, versionToUse, false, 'revert_change tool')
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
