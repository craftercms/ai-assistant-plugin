package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp

import java.net.URI
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Set

/** Outbound GET for {@link plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations.FetchHttpUrlTool}. */
final class HttpUrlFetch {

  private static final Logger log = LoggerFactory.getLogger(HttpUrlFetch)

  /**
   * Private constructor; not for direct use.
   */
private HttpUrlFetch() {}

  /**
   * Fetches remote or stream content for tool use.
   * @param absoluteUrl Caller-supplied input.
   * @param maxCharsOpt Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map fetch(String absoluteUrl, Integer maxCharsOpt) {
    if (!OutboundHttpPolicy.globallyEnabled()) {
      return [
        ok     : false,
        action : 'fetch_http_url',
        message: 'HTTP URL fetch is disabled (JVM aiassistant.httpFetch.enabled=false).'
      ]
    }
    String urlStr = (absoluteUrl ?: '').toString().trim()
    if (!urlStr) {
      throw new IllegalArgumentException('Missing required field: url (absolute http(s) URL)')
    }
    URI start
    try {
      start = new URI(urlStr)
    } catch (Throwable t) {
      return [ok: false, action: 'fetch_http_url', message: "Invalid URL: ${t.message}"]
    }
    String err0 = OutboundHttpPolicy.ssrfErrorForUri(start)
    if (err0) {
      return [ok: false, action: 'fetch_http_url', message: err0]
    }
    int maxChars = OutboundHttpPolicy.maxChars(maxCharsOpt)
    try {
      def ex = StudioAiSandboxHttp.getText(start, [
        connectTimeoutMs: 15_000,
        readTimeoutMs   : 60_000,
        maxRedirects    : 5,
        maxBodyChars    : maxChars,
        accept          :
          'text/html,text/css,text/plain,text/javascript,application/javascript,application/json,application/xhtml+xml,*/*;q=0.5',
        userAgent       : 'CrafterCMS-AI-Assistant-Studio-Plugin/1.0 (+https://craftercms.org)',
        ssrfCheck       : true
      ])
      if (ex.errorMessage && !ex.bodyText && ex.statusCode <= 0) {
        return [ok: false, action: 'fetch_http_url', message: ex.errorMessage]
      }
      int status = ex.statusCode
      String body = ex.bodyText ?: ''
      String ct = ex.contentType ?: ''
      String finalUrl = ex.finalUrl ?: start.toString()
      String ctLower = (ct ?: '').toString().toLowerCase(Locale.ROOT)
      boolean looksHtml =
        ctLower.contains('html') ||
          body.contains('<html') ||
          body.contains('<HTML') ||
          body.contains('<head') ||
          body.contains('<HEAD') ||
          body.contains('<link') ||
          body.contains('<LINK')
      List stylesheetHrefs = looksHtml ? collectStylesheetHrefs(body, 48) : []
      return [
        action         : 'fetch_http_url',
        ok             : status >= 200 && status < 300,
        statusCode     : status,
        finalUrl       : finalUrl,
        contentType    : ct,
        charCount      : body.length(),
        truncated      : ex.truncated,
        body           : body,
        stylesheetHrefs: stylesheetHrefs,
        redirects      : ex.redirectHops,
        message        : status >= 200 && status < 300
          ? 'Fetched URL body as UTF-8 text.'
          : (ex.errorMessage ?: "HTTP ${status} — body may be an error page or non-HTML.")
      ]
    } catch (Throwable t) {
      log.warn('FetchHttpUrl failed url={}: {}', absoluteUrl, t.toString())
      return [
        ok     : false,
        action : 'fetch_http_url',
        message: (t.message ?: t.toString())
      ]
    }
  }

  /**
   * Extracts link attr insensitive from repository XML or related text.
   * @param attrs Caller-supplied input.
   * @param name Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String extractLinkAttrInsensitive(String attrs, String name) {
    if (!attrs || !name) {
      return null
    }
    String q = java.util.regex.Pattern.quote(name.toString())
    def m = (attrs =~ /(?is)(?<![\w-])${q}\s*=\s*["']([^"']*)["']/)
    return m.find() ? m.group(1)?.toString()?.trim() : null
  }

  /**
   * Rel contains stylesheet token.
   * @param rel Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean relContainsStylesheetToken(String rel) {
    if (!rel) {
      return false
    }
    for (String tok : rel.toLowerCase(Locale.ROOT).trim().split(/\s+/)) {
      if ('stylesheet'.equals(tok)) {
        return true
      }
    }
    return false
  }

  /**
   * Collect stylesheet hrefs.
   * @param html Caller-supplied input.
   * @param max Caller-supplied input.
   * @return List<String> result.
   */
  private static List<String> collectStylesheetHrefs(String html, int max) {
    if (html == null || max < 1) {
      return []
    }
    int cap = Math.min(html.length(), 400_000)
    String s = html.substring(0, cap)
    Set<String> seen = new LinkedHashSet<>()
    List<String> out = []
    def push = { String href ->
      if (!href) {
        return
      }
      String t = href.trim()
      if (!t || seen.contains(t)) {
        return
      }
      seen.add(t)
      out.add(t)
    }
    def linkMatcher = (s =~ /(?is)<link\s([^>]+)>/)
    while (linkMatcher.find() && out.size() < max) {
      String attrs = linkMatcher.group(1)?.toString() ?: ''
      String href = extractLinkAttrInsensitive(attrs, 'href')
      String rel = extractLinkAttrInsensitive(attrs, 'rel')
      if (href && relContainsStylesheetToken(rel)) {
        push(href)
      }
    }
    return out
  }
}
