package plugins.org.craftercms.aiassistant.studio.repository

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Shared static helpers for preview URL/cookie handling (extracted from {@link plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations}).
 * Decodes {@code crafterPreview} fragments, trims dangerous cookies before Engine GETs, and rewrites Studio shell hash URLs into plain Engine preview URLs.
 */
final class StudioToolOperationsSupport {

  /**
   * Private constructor; not for direct use.
   */
private StudioToolOperationsSupport() {}

  /**
   * Performs cautious percent-decoding on arbitrary cookie/header fragments without mangling {@code '+'}.
   * Walks the string code-unit-wise emitting UTF-8 bytes for valid {@code %HH} escapes.
   * Falls back to copying untouched characters when escapes are malformed.
   */
  static String decodePercentEscapesUtf8PreservePlus(String s) {
    if (s == null || s.isEmpty()) {
      return s
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, s.length()))
    for (int i = 0; i < s.length();) {
      char c = s.charAt(i)
      if (c == '%' && i + 2 < s.length()) {
        int d1 = Character.digit(s.charAt(i + 1), 16)
        int d2 = Character.digit(s.charAt(i + 2), 16)
        if (d1 >= 0 && d2 >= 0) {
          out.write((d1 << 4) | d2)
          i += 3
          continue
        }
      }
      out.write(s.substring(i, i + 1).getBytes(StandardCharsets.UTF_8))
      i++
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8)
  }

  /**
   * Resolves {@code crafterPreview} from servlet attributes first, then cookie arrays, finally raw {@code Cookie} headers.
   * Applies {@link #decodePercentEscapesUtf8PreservePlus} when decoding succeeds.
   * Returns empty string when Studio never forwarded preview material for this request.
   */
  static String readCrafterPreviewTokenFromServletRequest(def request) {
    if (!request) {
      return ''
    }
    try {
      def a = request.getAttribute('aiassistant.previewToken')
      if (a != null) {
        def s = a.toString()?.trim()
        if (s) {
          return s
        }
      }
    } catch (Throwable ignored) {
    }
    try {
      def cookies = request.getCookies()
      if (cookies) {
        for (def c : cookies) {
          if (c?.name && 'crafterPreview'.equalsIgnoreCase(c.name as String)) {
            def v = c.value?.toString()?.trim()
            if (v) {
              try {
                return decodePercentEscapesUtf8PreservePlus(v)
              } catch (Throwable ignored2) {
                return v
              }
            }
          }
        }
      }
    } catch (Throwable ignored) {
    }
    try {
      def raw = request?.getHeader('Cookie')?.toString()
      if (raw?.trim()) {
        for (String part : raw.split(';')) {
          def p = part.trim()
          if (!p) {
            continue
          }
          int eq = p.indexOf('=')
          if (eq <= 0) {
            continue
          }
          def name = p.substring(0, eq).trim()
          if (!name || !'crafterPreview'.equalsIgnoreCase(name)) {
            continue
          }
          def v = eq + 1 < p.length() ? p.substring(eq + 1).trim() : ''
          if (v.startsWith('"') && v.endsWith('"') && v.length() >= 2) {
            v = v.substring(1, v.length() - 1).replace('\\"', '"').replace('\\\\', '\\')
          }
          if (v) {
            try {
              return decodePercentEscapesUtf8PreservePlus(v)
            } catch (Throwable ignored2) {
              return v
            }
          }
        }
      }
    } catch (Throwable ignored) {
    }
    return ''
  }

  /**
   * CrafterQ anonymous chat JWT ({@code X-CrafterQ-Chat-User}).
   * Checks {@code aiassistant.crafterQChatUser} servlet attribute first, then {@code X-CrafterQ-Chat-User} header.
   *
   * @param request servlet request (may be null)
   * @return chat JWT or empty string when not provided
   */
  static String readCrafterQChatUserFromServletRequest(def request) {
    if (!request) {
      return ''
    }
    try {
      def a = request.getAttribute('aiassistant.crafterQChatUser')
      if (a != null) {
        def s = a.toString()?.trim()
        if (s) {
          return s
        }
      }
    } catch (Throwable ignored) {
    }
    try {
      String h = request.getHeader('X-CrafterQ-Chat-User')?.toString()?.trim()
      if (h) {
        return h
      }
    } catch (Throwable ignored2) {
    }
    try {
      String h2 = request.getHeader('x-crafterq-chat-user')?.toString()?.trim()
      if (h2) {
        return h2
      }
    } catch (Throwable ignored3) {
    }
    return ''
  }

  /**
   * Quotes RFC6265-ish cookie attribute values when separators or CTL characters appear.
   * Escapes embedded quotes/backslashes when quoting is required.
   * Leaves simple tokens untouched for compact headers.
   */
  static String formatCookieAttributeValue(String val) {
    if (val == null) {
      return ''
    }
    boolean needQuotes = false
    for (int i = 0; i < val.length(); i++) {
      char c = val.charAt(i)
      int cp = (int) c
      if (c == ';' || c == '"' || c == '\\' || c == '#' || Character.isWhitespace(c) || cp < 0x21 || cp == 0x7f) {
        needQuotes = true
        break
      }
    }
    if (!needQuotes) {
      return val
    }
    return '"' + val.replace('\\', '\\\\').replace('"', '\\"') + '"'
  }

  /**
   * Denylists cookie names that must never reach Engine preview fetches (session hijack vectors).
   * Always strips {@code JSESSIONID} and refresh tokens plus JVM-provided comma lists.
   * Keeps forwarded Studio cookies minimal yet sufficient for Experience Builder shells.
   */
  static boolean stripCookieNameForPreviewEngineFetch(String cookieName) {
    if (!cookieName) {
      return false
    }
    String n = cookieName.trim().toLowerCase(Locale.ROOT)
    if ('jsessionid'.equals(n)) {
      return true
    }
    if ('refresh_token'.equals(n)) {
      return true
    }
    try {
      def extra = System.getProperty('aiassistant.preview.fetch.stripCookieNames')?.toString()?.trim()
      if (extra) {
        for (String part : extra.split(',')) {
          def p = part.trim().toLowerCase(Locale.ROOT)
          if (p && p == n) {
            return true
          }
        }
      }
    } catch (Throwable ignored) {
    }
    return false
  }

  /**
   * Converts Studio preview shell URLs ({@code /studio/preview#/…}) into Engine-compatible absolute URLs.
   * Parses hash-query fragments for {@code page} / {@code url} plus {@code crafterSite}.
   * Leaves non-hash URLs untouched so callers can pass already-normalized Engine links.
   */
  static String rewriteStudioPreviewShellUrlForEngineFetch(String fullUrl, String siteIdFallback) {
    def u = (fullUrl ?: '').toString().trim()
    if (!u || !u.contains('#')) {
      return u
    }
    int hash = u.indexOf('#')
    String before = u.substring(0, hash)
    String frag = u.substring(hash + 1)
    if (!frag.startsWith('/?') && !frag.startsWith('?')) {
      return u
    }
    String q = frag.startsWith('/?') ? frag.substring(2) : frag.substring(1)
    Map<String, String> params = parseAmpQueryString(q)
    String page = params.page ?: params.url ?: ''
    String site = params.crafterSite ?: siteIdFallback ?: ''
    if (!page) {
      return u
    }
    int schemeEnd = before.indexOf('://')
    if (schemeEnd < 0) {
      return u
    }
    int pathStart = before.indexOf('/', schemeEnd + 3)
    String origin = pathStart > 0 ? before.substring(0, pathStart) : before
    StringBuilder out = new StringBuilder(origin)
    if (!page.startsWith('/')) {
      out.append('/')
    }
    out.append(page)
    if (site) {
      out.append(page.contains('?') ? '&' : '?').append('crafterSite=').append(URLEncoder.encode(site, 'UTF-8'))
    }
    return out.toString()
  }

  /**
   * Parses {@code application/x-www-form-urlencoded} style query strings using {@code &} separators.
   * URL-decodes keys/values best-effort while ignoring malformed segments.
   * Returns a mutable map mirroring Groovy-friendly {@code [:]} semantics.
   */
  static Map<String, String> parseAmpQueryString(String q) {
    Map<String, String> m = [:]
    if (!q) {
      return m
    }
    for (String part : q.split('&')) {
      int eq = part.indexOf('=')
      if (eq < 0) {
        continue
      }
      String k = part.substring(0, eq).trim()
      String v = eq + 1 < part.length() ? part.substring(eq + 1) : ''
      try {
        k = URLDecoder.decode(k, 'UTF-8')
        v = URLDecoder.decode(v, 'UTF-8')
      } catch (Throwable ignored) {
      }
      if (k) {
        m[k] = v
      }
    }
    return m
  }

  /**
   * Removes named query parameters case-insensitively while preserving hash fragments.
   * Rebuilds the query string only when surviving parameters remain.
   * Used to strip preview tokens duplicated between cookies and URLs.
   */
  static String removeQueryParamsCaseInsensitive(String url, Collection<String> paramNames) {
    def full = (url ?: '').toString()
    if (!full) {
      return full
    }
    int hash = full.indexOf('#')
    String beforeHash = hash >= 0 ? full.substring(0, hash) : full
    String frag = hash >= 0 ? full.substring(hash) : ''
    int q = beforeHash.indexOf('?')
    if (q < 0) {
      return full
    }
    String base = beforeHash.substring(0, q)
    String query = beforeHash.substring(q + 1)
    if (!query) {
      return base + frag
    }
    Set<String> drop = [] as Set
    for (String n : paramNames) {
      if (n) {
        drop.add(n.toLowerCase(Locale.ROOT))
      }
    }
    List<String> kept = []
    for (String part : query.split('&')) {
      int eq = part.indexOf('=')
      String key = (eq >= 0 ? part.substring(0, eq) : part).trim()
      String keyLc = key.toLowerCase(Locale.ROOT)
      if (!drop.contains(keyLc)) {
        kept.add(part)
      }
    }
    if (kept.isEmpty()) {
      return base + frag
    }
    return base + '?' + String.join('&', kept) + frag
  }
}
