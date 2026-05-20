package plugins.org.craftercms.aiassistant.tools.cms.support

import plugins.org.craftercms.aiassistant.tools.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.tools.cms.support.CmsRepositorySupport
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsContentVersionHistory {

  private static final Logger log = LoggerFactory.getLogger(CmsContentVersionHistory)

  private CmsContentVersionHistory() {}
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
