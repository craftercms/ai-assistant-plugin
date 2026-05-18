package plugins.org.craftercms.aiassistant.tools.operations

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Shared static helpers for preview URL/cookie handling (extracted from {@link plugins.org.craftercms.aiassistant.tools.StudioToolOperations}).
 */
final class StudioToolOperationsSupport {

  private StudioToolOperationsSupport() {}

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
