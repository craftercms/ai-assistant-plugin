package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
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
    final int maxRedirects = 5
    int redirectCount = 0
    URI currentUri = start
    HttpURLConnection conn = null
    InputStream inStream = null
    try {
      while (true) {
        String hopErr = OutboundHttpPolicy.ssrfErrorForUri(currentUri)
        if (hopErr) {
          return [ok: false, action: 'fetch_http_url', message: hopErr]
        }
        URL url = currentUri.toURL()
        conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod('GET')
        conn.setInstanceFollowRedirects(false)
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(60_000)
        conn.setRequestProperty(
          'Accept',
          'text/html,text/css,text/plain,text/javascript,application/javascript,application/json,application/xhtml+xml,*/*;q=0.5'
        )
        conn.setRequestProperty('Accept-Encoding', 'identity')
        conn.setRequestProperty('User-Agent', 'CrafterCMS-AI-Assistant-Studio-Plugin/1.0 (+https://craftercms.org)')
        int status = conn.responseCode
        if (status >= 300 && status < 400 && redirectCount < maxRedirects) {
          String loc = conn.getHeaderField('Location')?.toString()?.trim()
          try {
            conn.disconnect()
          } catch (Throwable ignored) {
          }
          conn = null
          if (!loc) {
            return [
              ok        : false,
              action    : 'fetch_http_url',
              statusCode: status,
              message   : "HTTP ${status} redirect without Location header."
            ]
          }
          try {
            currentUri = currentUri.resolve(new URI(loc))
          } catch (Throwable t) {
            return [ok: false, action: 'fetch_http_url', message: "Invalid redirect Location: ${t.message}"]
          }
          redirectCount++
          continue
        }
        String ct = (conn.getContentType() ?: '').toString()
        if (status >= 300 && status < 400) {
          return [
            ok        : false,
            action    : 'fetch_http_url',
            statusCode: status,
            message   : "Exceeded maximum of ${maxRedirects} redirect hops (or redirect could not be applied).",
            location  : conn.getHeaderField('Location')
          ]
        }
        inStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream()
        StringBuilder sb = new StringBuilder(Math.min(maxChars + 16, 65536))
        boolean truncated = false
        int total = 0
        if (inStream != null) {
          BufferedReader reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))
          char[] cbuf = new char[8192]
          while (true) {
            int n = reader.read(cbuf)
            if (n < 0) {
              break
            }
            if (total + n <= maxChars) {
              sb.append(cbuf, 0, n)
              total += n
            } else {
              int take = maxChars - total
              if (take > 0) {
                sb.append(cbuf, 0, take)
                total = maxChars
              }
              truncated = true
              break
            }
          }
        }
        String body = sb.toString()
        String finalUrl = ''
        try {
          finalUrl = conn.getURL()?.toString() ?: currentUri.toString()
        } catch (Throwable ignored) {
          finalUrl = currentUri.toString()
        }
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
          truncated      : truncated,
          body           : body,
          stylesheetHrefs: stylesheetHrefs,
          redirects      : redirectCount,
          message        : status >= 200 && status < 300
            ? 'Fetched URL body as UTF-8 text.'
            : "HTTP ${status} — body may be an error page or non-HTML."
        ]
      }
    } catch (Throwable t) {
      log.warn('FetchHttpUrl failed url={}: {}', absoluteUrl, t.toString())
      return [
        ok     : false,
        action : 'fetch_http_url',
        message: (t.message ?: t.toString())
      ]
    } finally {
      try {
        inStream?.close()
      } catch (Throwable ignored) {
      }
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {
      }
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
