package plugins.org.craftercms.aiassistant.tools.cms

import plugins.org.craftercms.aiassistant.authoring.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsWriteContent
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSupport

/**
 * LLM tool that persists full-item {@code contentXml} to a repository path via Studio's write pipeline
 * (blocked for the protected form item when client-side apply is active).
 */
class WriteContentTool extends AbstractStudioAiTool {

  /** Returns the Spring AI wire name {@code WriteContent}. */
  @Override
  String wireName() { 'WriteContent' }

  /** Site-overridable tool description from {@link ToolPrompts}. */
  @Override
  String description() { ToolPrompts.DESC_WRITE_CONTENT }

  /** JSON Schema for path, {@code contentXml}, optional {@code unlock}, and {@code siteId}. */
  @Override
  String inputSchemaJson() { StudioAiToolSchemas.WRITE_CONTENT }

  /** Disabled when orchestration sets {@code fullSuppressRepoWrites} on the tool context. */
  @Override
  boolean enabled(StudioAiToolContext ctx) {
    return !ctx.fullSuppressRepoWrites
  }

  /**
   * Rejects writes to the form-engine protected path (returns client-apply guidance), then calls
   * {@link plugins.org.craftercms.aiassistant.tools.cms.support.CmsWriteContent#write} with resolved site and XML body.
   */
  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    def path = StudioAiToolSupport.repoPathFromToolInput(input)
    if (ctx.pathProtectFormItem) {
      def p = AuthoringPreviewContext.normalizeRepoPath(path)
      if (p && p == ctx.normProtectedFormItemPath) {
        return [
          ok: false,
          blockedForFormClientApply: true,
          path: p,
          message:
            'WriteContent blocked: this path is the Studio form item with client-side apply. Put field edits in aiassistantFormFieldUpdates JSON in your final reply. You may still call WriteContent for other repository paths.',
          nextStep: 'Return aiassistantFormFieldUpdates for this item; use WriteContent only for paths other than this one.'
        ]
      }
    }
    if (!path) throw new IllegalArgumentException('Missing required field: path (or contentPath)')
    String siteId = ctx.ops.resolveEffectiveSiteId(input?.siteId?.toString()?.trim() ?: '')
    String contentXml = input?.contentXml?.toString()
    if (!contentXml?.trim()) {
      throw new IllegalArgumentException('Missing required field: contentXml')
    }
    return CmsWriteContent.write(
      ctx.ops,
      siteId,
      path,
      contentXml,
      input?.unlock != null ? input.unlock as String : 'true'
    ) as Map
  }
}
