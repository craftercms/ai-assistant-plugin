package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import java.util.Locale

/**
 * OpenSearch / index helpers that do not assume a site content model.
 * Crafter indexes dynamic field names; use naming conventions and source key scans, not fixed field ids.
 */
final class CmsIndexedContentFields {

  private static final Set<String> SKIP_INDEX_KEYS = Collections.unmodifiableSet([
    'localId', 'content-type', 'objectId', 'objectGroupId', 'merge-strategy', 'display-template',
    'no-template-required', 'file-name', 'folder-name', 'createdDate', 'createdDate_dt',
    'lastModifiedDate', 'lastModifiedDate_dt', 'disabled', 'placeInNav', 'orderDefault_f'
  ] as Set)

  private CmsIndexedContentFields() {}

  /** Field boosts for authoring search — wildcard suffixes match any indexed copy field. */
  static List<String> searchMultiMatchBoostFields() {
    return [
      'internal-name^3',
      'navLabel^2',
      'seoDescription_t^2',
      '*_t^2',
      '*_html'
    ]
  }

  /** Human-readable label from an OpenSearch hit source without assuming {@code title_t}. */
  static String displayTitleFromIndexSource(Map src) {
    if (!(src instanceof Map) || src.isEmpty()) {
      return ''
    }
    String internal = src.get('internal-name')?.toString()?.trim()
    if (internal) {
      return internal
    }
    String titleLike = firstStringValueForKeyPattern(src, { String k ->
      String kl = k.toLowerCase(Locale.ROOT)
      kl.endsWith('_t') && (kl.contains('title') || kl.contains('subject') || kl.contains('headline'))
    })
    if (titleLike) {
      return titleLike
    }
    for (String fixed : ['navLabel', 'title']) {
      String v = src.get(fixed)?.toString()?.trim()
      if (v) {
        return v
      }
    }
    return firstStringValueForKeyPattern(src, { String k -> k.endsWith('_t') })
  }

  /** Plain-text snippet from index source — prefers rich HTML fields, then text fields. */
  static String snippetFromIndexSource(Map src, int maxLen = 320) {
    if (!(src instanceof Map) || src.isEmpty()) {
      return ''
    }
    String html = firstStringValueForKeyPattern(src, { String k -> k.endsWith('_html') })
    String plain = html ? stripHtml(html) : ''
    if (!plain) {
      plain = firstStringValueForKeyPattern(src, { String k ->
        k.endsWith('_t') && !SKIP_INDEX_KEYS.contains(k)
      }) ?: ''
    }
    return capPlain(plain, maxLen)
  }

  private static String firstStringValueForKeyPattern(Map src, Closure<Boolean> keyPredicate) {
    List<String> keys = new ArrayList<>(src.keySet())
    Collections.sort(keys)
    for (String k : keys) {
      if (!k || SKIP_INDEX_KEYS.contains(k)) {
        continue
      }
      if (!keyPredicate.call(k)) {
        continue
      }
      String v = src.get(k)?.toString()?.trim()
      if (v) {
        return v
      }
    }
    return ''
  }

  private static String stripHtml(String html) {
    return (html ?: '').replaceAll(/(?is)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
  }

  private static String capPlain(String s, int maxLen) {
    String t = (s ?: '').trim()
    if (!t || t.length() <= maxLen) {
      return t
    }
    return t.substring(0, Math.max(0, maxLen - 1)) + '…'
  }
}
