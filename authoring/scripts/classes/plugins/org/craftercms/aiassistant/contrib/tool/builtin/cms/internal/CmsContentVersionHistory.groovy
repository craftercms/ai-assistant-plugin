package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsRepositorySupport
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsContentVersionHistory {

  private static final Logger log = LoggerFactory.getLogger(CmsContentVersionHistory)

  /**
   * Private constructor; not for direct use.
   */
private CmsContentVersionHistory() {}
  /**
   * Lists matching items for the model or author.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param path Studio or repository context for this call.
   * @return List<Map> result.
   */
  static List<Map> list(StudioToolOperations ops, String siteId, String path) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      def normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      def versions = ops.contentServiceBean.getContentVersionHistory(siteId, normalized)
      if (versions == null) return []
      def out = []
      for (def v : versions) {
        if (v == null) continue
        def md = null
        try {
          md = v.getModifiedDate()?.toString()
        } catch (Throwable ignored) {}
        out.add([
          versionNumber: v.getVersionNumber()?.toString(),
          modifiedDate : md,
          revertible   : v.isRevertible(),
          comment      : v.getComment()?.toString(),
          committer    : v.getCommitter()?.toString(),
          path         : v.getPath()?.toString()
        ])
      }
      out
    }
  }

}
