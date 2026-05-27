package plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.engine.context.AuthoringPreviewContext
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsGetContent
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsContentVersionHistory
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.cms.internal.CmsRepositorySupport
import java.util.Locale
import java.util.regex.Pattern
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsRevertChange {

  private static final Logger log = LoggerFactory.getLogger(CmsRevertChange)

  /**
   * Private constructor; not for direct use.
   */
private CmsRevertChange() {}
  /**
   * Extracts xml field rough plain text from repository XML or related text.
   * @param contentXml Caller-supplied input.
   * @param fieldId Identifier for the target resource.
   * @return Text result, or empty or null when unavailable.
   */
  private static String extractXmlFieldRoughPlainText(String contentXml, String fieldId) {
    if (!contentXml?.trim() || !fieldId?.trim()) {
      return ''
    }
    String tagQuoted = Pattern.quote(fieldId.trim())
    def mCdata = (contentXml =~ "(?is)<${tagQuoted}>\\s*<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>\\s*</${tagQuoted}>")
    if (mCdata.find()) {
      return roughPlainTextFromHtml(mCdata.group(1))
    }
    def mEsc = (contentXml =~ "(?is)<${tagQuoted}>([\\s\\S]*?)</${tagQuoted}>")
    if (mEsc.find()) {
      return roughPlainTextFromHtml(mEsc.group(1))
    }
    return ''
  }

  /**
   * Removes tags with regex then collapses whitespace.
   * Decodes a handful of HTML entities.
   * Produces comparable plain text for substring searches.
   */
  private static String roughPlainTextFromHtml(String html) {
    if (!html?.trim()) {
      return ''
    }
    return html
      .replaceAll('(?is)<script[^>]*>[\\s\\S]*?</script>', ' ')
      .replaceAll('(?is)<style[^>]*>[\\s\\S]*?</style>', ' ')
      .replaceAll('<[^>]+>', ' ')
      .replaceAll('\\s+', ' ')
      .trim()
  }

  /**
   * Lowercases both operands using ROOT locale.
   * Handles blank needles gracefully.
   * Feeds guard rails comparing author-supplied snippets.
   */
  private static boolean plainTextContainsIgnoreCase(String haystackPlain, String needle) {
    if (!needle?.trim() || !haystackPlain) {
      return false
    }
    return haystackPlain.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT))
  }

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
        String matched = matchContentVersion(
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
   * Uses {@link #getContent} with each {@code versionNumber} as ref when Studio accepts it.
   */
  static String matchContentVersion(StudioToolOperations ops, 
    String siteId,
    String path,
    List<String> mustContainSnippets,
    String fieldId = null,
    int maxScan = 40
  ) {
    ops.runWithStudioSecurity {
      siteId = ops.resolveEffectiveSiteId(siteId)
      def normalized = CmsRepositorySupport.normalizeLeadingSlash(path, 'path')
      List<String> snippets = []
      for (def sn : (mustContainSnippets ?: [])) {
        String t = (sn ?: '').toString().trim()
        if (t) {
          snippets.add(t)
        }
      }
      if (snippets.isEmpty()) {
        return null
      }
      List history = CmsContentVersionHistory.list(ops, siteId, normalized)
      if (history == null || history.isEmpty()) {
        return null
      }
      int limit = Math.min(history.size(), Math.max(1, maxScan))
      for (int i = 0; i < limit; i++) {
        def e = history[i]
        if (e == null || e.revertible == false) {
          continue
        }
        String vn = e.versionNumber?.toString()?.trim()
        if (!vn) {
          continue
        }
        String xml = ''
        try {
          Map item = CmsGetContent.read(ops, siteId, normalized, vn) as Map
          xml = (item?.contentXml ?: '').toString()
        } catch (Throwable ignored) {
          continue
        }
        if (!xml?.trim()) {
          continue
        }
        String plain = fieldId?.trim() ?
          extractXmlFieldRoughPlainText(xml, fieldId.trim()) :
          roughPlainTextFromHtml(xml)
        if (!plain) {
          continue
        }
        boolean allMatch = true
        for (String snip : snippets) {
          if (!plainTextContainsIgnoreCase(plain, snip)) {
            allMatch = false
            break
          }
        }
        if (allMatch) {
          return vn
        }
      }
      return null
    }
  }

}
