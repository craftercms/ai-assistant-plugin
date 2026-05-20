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
}
