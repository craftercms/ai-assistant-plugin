package plugins.org.craftercms.aiassistant.studio.engine.turn

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Extracts page title text from fetched HTML and flags shallow URLs / weak titles.
 * Generic heuristics only — no domain- or topic-specific rules.
 */
final class AuthoringFetchedPageFacts {

  private static final Pattern META_OG_TITLE =
    Pattern.compile('(?is)<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)["\']')
  private static final Pattern META_OG_TITLE_REV =
    Pattern.compile('(?is)<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:title["\']')
  private static final Pattern TAG_TITLE = Pattern.compile('(?is)<title[^>]*>([^<]+)</title>')
  private static final Pattern TAG_H1 = Pattern.compile('(?is)<h1[^>]*>([^<]{4,240})</h1>')
  private static final Pattern HEADLINE_LINK =
    Pattern.compile('(?is)<h[23][^>]*>\\s*<a[^>]*>([^<]{12,220})</a>')
  private static final Pattern HEADLINE_PLAIN =
    Pattern.compile('(?is)<h[23][^>]*>([^<]{12,220})</h[23]>')

  private AuthoringFetchedPageFacts() {}

  /**
   * Best substantive fact line from fetched HTML (e.g. a lead item on an index page), not the site document title.
   */
  static String extractPrimaryFact(String htmlBody, String url) {
    String html = (htmlBody ?: '').toString()
    String u = (url ?: '').trim()
    if (!html.trim()) {
      return ''
    }
    List<String> candidates = []
    for (Pattern p : [HEADLINE_LINK, HEADLINE_PLAIN]) {
      Matcher m = p.matcher(html)
      while (m.find()) {
        String t = cleanText(m.group(1))
        if (t && !isWeakPageTitle(t, u)) {
          candidates.add(t)
        }
      }
    }
    if (!candidates.isEmpty()) {
      return candidates.get(0)
    }
    Map meta = extract(html, u)
    if (meta.pageTitle && !Boolean.TRUE.equals(meta.weakTitle) && !Boolean.TRUE.equals(meta.shallowUrl)) {
      return meta.pageTitle.toString()
    }
    return ''
  }

  /**
   * @return map with {@code pageTitle}, {@code shallowUrl}, {@code weakTitle}, {@code url}
   */
  static Map extract(String htmlBody, String url) {
    String u = (url ?: '').trim()
    String html = (htmlBody ?: '').toString()
    boolean shallow = isShallowUrl(u)
    String pageTitle = pickPageTitle(html, u)
    boolean weak = isWeakPageTitle(pageTitle, u)
    return [
      pageTitle : pageTitle ?: '',
      shallowUrl: shallow,
      weakTitle : weak,
      url       : u
    ]
  }

  /** True when the URL path is empty, root, or a single segment (likely index, not a deep resource). */
  static boolean isShallowUrl(String url) {
    String u = (url ?: '').trim()
    if (!u) {
      return false
    }
    try {
      URI uri = new URI(u)
      String path = (uri.path ?: '').trim()
      if (!path || '/'.equals(path)) {
        return true
      }
      String stripped = path.replaceAll('/+$', '')
      int slashes = stripped.count('/')
      return slashes <= 1
    } catch (Throwable ignored) {
      return false
    }
  }

  /** True when title is empty, very short, or matches the site host name (site label, not page content). */
  static boolean isWeakPageTitle(String title, String url = '') {
    String t = (title ?: '').trim()
    if (!t) {
      return true
    }
    if (t.length() < 12) {
      return true
    }
    if (url && isShallowUrl(url) && t.length() > 50) {
      return true
    }
    if (url) {
      try {
        String host = new URI(url).host?.toLowerCase(Locale.ROOT) ?: ''
        String hostBare = host.replaceFirst('^www\\.', '')
        String lower = t.toLowerCase(Locale.ROOT)
        if (hostBare && (lower == hostBare || lower == host)) {
          return true
        }
      } catch (Throwable ignored) {
      }
    }
    return false
  }

  private static String pickPageTitle(String html, String url) {
    if (!html?.trim()) {
      return ''
    }
    String fromOg = firstGroup(META_OG_TITLE, html)
    if (!fromOg) {
      fromOg = firstGroup(META_OG_TITLE_REV, html)
    }
    if (fromOg && !isWeakPageTitle(fromOg, url)) {
      return cleanText(fromOg)
    }
    Matcher h1 = TAG_H1.matcher(html)
    while (h1.find()) {
      String cand = cleanText(h1.group(1))
      if (cand && !isWeakPageTitle(cand, url)) {
        return cand
      }
    }
    String fromTitle = firstGroup(TAG_TITLE, html)
    if (fromTitle && !isWeakPageTitle(fromTitle, url)) {
      return cleanText(fromTitle)
    }
    if (fromOg) {
      return cleanText(fromOg)
    }
    return fromTitle ? cleanText(fromTitle) : ''
  }

  private static String firstGroup(Pattern p, String html) {
    Matcher m = p.matcher(html)
    return m.find() ? (m.group(1) ?: '').trim() : ''
  }

  private static String cleanText(String raw) {
    String t = (raw ?: '').replaceAll('(?is)<[^>]+>', ' ')
    t = t.replaceAll('\\s+', ' ').trim()
    if (t.length() > 240) {
      t = t.substring(0, 240) + '…'
    }
    return t
  }
}
