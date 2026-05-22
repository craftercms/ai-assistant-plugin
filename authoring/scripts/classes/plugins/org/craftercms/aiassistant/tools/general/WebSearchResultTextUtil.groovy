package plugins.org.craftercms.aiassistant.tools.general

/**
 * Shared normalization for open-web search hit titles/snippets (DuckDuckGo HTML, SerpAPI JSON).
 */
final class WebSearchResultTextUtil {

  private WebSearchResultTextUtil() {}

  static String stripHtml(String htmlFragment) {
    if (!htmlFragment) {
      return ''
    }
    String s = htmlFragment.toString()
    s = s.replaceAll('(?is)<[^>]+>', ' ')
    s = decodeHtmlEntities(s)
    return s.replaceAll('\\s+', ' ').trim()
  }

  static String decodeHtmlEntities(String s) {
    if (!s) {
      return ''
    }
    return s
      .replace('&amp;', '&')
      .replace('&lt;', '<')
      .replace('&gt;', '>')
      .replace('&quot;', '"')
      .replace('&#39;', "'")
      .replace('&nbsp;', ' ')
  }

  /** Drops non-http(s) URLs and known search-provider redirect hosts. */
  static boolean skipResultUrl(String url) {
    String u = (url ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (!u.startsWith('http://') && !u.startsWith('https://')) {
      return true
    }
    return u.contains('duckduckgo.com/') || u.contains('duck.com/')
  }

  /**
   * When the query was expanded for content-management industry research, drop US healthcare CMS hits.
   */
  static boolean skipHealthcareCmsResult(String url, String title, String snippet) {
    String u = (url ?: '').toString().trim().toLowerCase(Locale.ROOT)
    if (u.contains('medicare.gov') || u.contains('medicaid.gov') || u.contains('cms.gov')) {
      return true
    }
    String text = ((title ?: '') + ' ' + (snippet ?: '')).toString().toLowerCase(Locale.ROOT)
    if (!text.contains('medicare') && !text.contains('medicaid')) {
      return false
    }
    return text.contains('enrollment') ||
      text.contains('moratorium') ||
      text.contains('hospice') ||
      text.contains('home health') ||
      text.contains('medicare & medicaid') ||
      text.contains('centers for medicare')
  }
}
