package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsContentVersionHistory
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal.CmsRepositorySupport
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsRevertChange {

  private static final Logger log = LoggerFactory.getLogger(CmsRevertChange)

  /**
   * Private constructor; not for direct use.
   */
private CmsRevertChange() {}

  /**
   * Revert item.
   * @param ops Caller-supplied input.
   * @param siteId Studio or repository context for this call.
   * @param path Studio or repository context for this call.
   * @param version Caller-supplied input.
   * @param major Caller-supplied input.
   * @param comment Caller-supplied input.
   */
  static void revertItem(StudioToolOperations ops, String siteId, String path, String version, boolean major = false, String comment = null) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      def normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      def ver = (version ?: '').toString().trim()
      if (!ver) throw new IllegalArgumentException('Missing required field: version (Studio item version from getContentVersionHistory)')
      String c = (comment ?: 'revert_change tool').toString().trim() ?: 'revert_change tool'
      ops.cstudioContentServiceBean.revertContentItem(siteId, normalized, ver, major, c)
    }
  }

  /**
   * Picks the immediate prior revertible version from v2 history (index {@code 1} when index {@code 0} is current head).
   */
  static String previousVersion(StudioToolOperations ops, String siteId, String path) {
    def history = CmsContentVersionHistory.list(ops, siteId, path)
    if (history == null || history.isEmpty()) {
      throw new IllegalStateException('No version history for path')
    }
    if (history.size() < 2) {
      throw new IllegalStateException('No previous version to revert to (history has only the current entry)')
    }
    def prev = history[1]
    if (prev.revertible == false) {
      for (int i = 2; i < history.size(); i++) {
        def e = history[i]
        if (e.revertible != false && e.versionNumber) {
          return e.versionNumber.toString()
        }
      }
      throw new IllegalStateException('No revertible older version found in history')
    }
    def vn = prev.versionNumber?.toString()?.trim()
    if (!vn) throw new IllegalStateException('Previous history entry has no versionNumber')
    return vn
  }

  /**
   * Oldest revertible {@code versionNumber} in Studio history (last revertible entry in v2 list order).
   */
  static String oldestVersion(StudioToolOperations ops, String siteId, String path) {
    def history = CmsContentVersionHistory.list(ops, siteId, path)
    if (history == null || history.isEmpty()) {
      throw new IllegalStateException('No version history for path')
    }
    for (int i = history.size() - 1; i >= 0; i--) {
      def e = history[i]
      if (e == null || e.revertible == false) {
        continue
      }
      def vn = e.versionNumber?.toString()?.trim()
      if (vn) {
        return vn
      }
    }
    throw new IllegalStateException('No revertible oldest version found in history')
  }

  /**
   * Resolves {@code version} for {@code revert_change} from explicit id, initial/oldest, content match, or previous step.
   *
   * @return map with {@code version} (String), {@code selection} (initial|content_match|previous|explicit)
   */
  static Map resolveVersionSelection(StudioToolOperations ops, String siteId, String path, Map input) {
    siteId = ops.resolveEffectiveSiteId(siteId)
    def normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
    boolean revertToInitial = AuthoringPreviewContext.isTruthy(input?.revertToInitial) ||
      AuthoringPreviewContext.isTruthy(input?.revertToOldest) ||
      AuthoringPreviewContext.isTruthy(input?.revertToFirst)
    boolean revertToPrevious = AuthoringPreviewContext.isTruthy(input?.revertToPrevious)
    def versionArg = input?.version?.toString()?.trim()
    if (!versionArg) {
      versionArg = input?.itemVersion?.toString()?.trim()
    }
    def revertType = input?.revertType?.toString()?.trim()
    def semanticRt = revertType && ['content', 'template', 'contenttype'].contains(revertType.toLowerCase())
    if (!versionArg && revertType && !semanticRt) {
      versionArg = revertType
    }
    List<String> contentContains = []
    def rawContains = input?.contentContains ?: input?.mustContain
    if (rawContains instanceof Collection) {
      for (def item : rawContains) {
        String t = (item ?: '').toString().trim()
        if (t) {
          contentContains.add(t)
        }
      }
    } else {
      String one = (rawContains ?: '').toString().trim()
      if (one) {
        contentContains.add(one)
      }
    }
    String contentFieldId = (input?.contentFieldId ?: input?.fieldId ?: '').toString().trim()
    if (versionArg) {
      return [version: versionArg, selection: 'explicit']
    }
    if (revertToInitial) {
      return [version: oldestVersion(ops, siteId, normalized), selection: 'initial']
    }
    if (revertToPrevious) {
      if (!contentContains.isEmpty()) {
        String matched = CmsFindContentVersion.matchNewestByContentContains(
          ops, siteId, normalized, contentContains, contentFieldId ?: null
        )
        if (matched) {
          return [version: matched, selection: 'content_match']
        }
      }
      return [version: previousVersion(ops, siteId, normalized), selection: 'previous']
    }
    if (semanticRt) {
      throw new IllegalArgumentException(
        'revertType content/template/contentType is not a Studio version id. Call GetContentVersionHistory and pass version=<versionNumber>, revertToInitial:true for the oldest revertible version, or revertToPrevious:true for one step back.'
      )
    }
    throw new IllegalArgumentException(
      'Missing version: pass version (versionNumber from GetContentVersionHistory), revertToInitial:true for the oldest revertible version, or revertToPrevious:true for one step back.'
    )
  }

  /**
   * Newest revertible history entry whose XML (optionally one field) contains every snippet (case-insensitive).
   * Delegates to {@link CmsFindContentVersion}.
   */
  static String matchContentVersion(
    StudioToolOperations ops,
    String siteId,
    String path,
    List<String> mustContainSnippets,
    String fieldId = null,
    int maxScan = 40
  ) {
    return CmsFindContentVersion.matchNewestByContentContains(
      ops, siteId, path, mustContainSnippets, fieldId, maxScan
    )
  }

}
