package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.cms.internal

import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperationsSupport
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
/** CMS tool implementation extracted from StudioToolOperations. */
final class CmsPreviewHtmlFetch {

  private static final Logger log = LoggerFactory.getLogger(CmsPreviewHtmlFetch)

  /**
   * Private constructor; not for direct use.
   */
private CmsPreviewHtmlFetch() {}
  /**
   * Max chars.
   * @return int result.
   */
  private static int maxChars() {
    return StudioAiPlatformSettings.propertyInt('aiassistant.preview.fetch.maxChars', 400_000, 4096, 2_000_000)
  }

  /**
   * Starts from frozen servlet Cookie headers.
   * Appends crafterPreview + crafterSite when missing.
   * Filters forbidden cookie names via StudioToolOperationsSupport helpers.
   */
  private static String buildCookieHeader(StudioToolOperations ops, String crafterPreviewTokenResolved, String siteIdForCookie) {
    def tok = (crafterPreviewTokenResolved ?: '').toString().trim()
    if (!tok) {
      return ''
    }
    String site = (siteIdForCookie ?: '').toString().trim()
    String raw = (ops.frozenCookieHeaderFromRequest ?: '').trim()
    if (!raw) {
      try {
        raw = ops.request?.getHeader('Cookie')?.toString()?.trim() ?: ''
      } catch (Throwable ignored) {
      }
    }
    List<String> segments = []
    if (raw) {
      for (String part : raw.split(';')) {
        def p = part.trim()
        if (!p) {
          continue
        }
        int eq = p.indexOf('=')
        String name = eq > 0 ? p.substring(0, eq).trim() : ''
        if (name && StudioToolOperationsSupport.stripCookieNameForPreviewEngineFetch(name)) {
          continue
        }
        if (name && 'crafterPreview'.equalsIgnoreCase(name)) {
          continue
        }
        if (site && 'crafterSite'.equalsIgnoreCase(name)) {
          continue
        }
        segments.add(p)
      }
    }
    segments.add('crafterPreview=' + StudioToolOperationsSupport.formatCookieAttributeValue(tok))
    if (site) {
      segments.add('crafterSite=' + StudioToolOperationsSupport.formatCookieAttributeValue(site))
    }
    return segments.join('; ')
  }

  /**
   * Checks host against Studio allowlists / loopback guards.
   * Reads JVM aiassistant.preview.fetch.allowHosts overrides.
   * Blocks SSRF-style fetches initiated by tools.
   */
  private static boolean hostAllowed(StudioToolOperations ops, String host) {
    if (!host) return false
    String h = host.toLowerCase(Locale.ROOT)
    String srv = ''
    try {
      srv = ops.request?.getServerName()?.toString()?.trim()?.toLowerCase(Locale.ROOT) ?: ''
    } catch (Throwable ignored) {}
    if (srv && h == srv) return true
    if (h == 'localhost' || h == '127.0.0.1' || h == '[::1]') return true
    def extra = StudioAiPlatformSettings.property('aiassistant.preview.fetch.allowedHosts', '')?.trim()
    if (extra) {
      for (String part : extra.split(',')) {
        def p = part.trim().toLowerCase(Locale.ROOT)
        if (p && h == p) return true
      }
    }
    return false
  }

  /**
   * Fetches remote or stream content for tool use.
   * @param ops Caller-supplied input.
   * @param absoluteUrl Caller-supplied input.
   * @param toolPreviewToken Caller-supplied input.
   * @param siteIdOpt Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map fetch(StudioToolOperations ops, String absoluteUrl, String toolPreviewToken, String siteIdOpt) {
    def urlStr = (absoluteUrl ?: '').toString().trim()
    if (!urlStr) {
      throw new IllegalArgumentException('Missing required field: url (absolute http(s) preview URL, or previewUrl alias)')
    }
    String siteForQuery = (siteIdOpt ?: '').toString().trim()
    if (!siteForQuery) {
      try {
        siteForQuery = ops.resolveEffectiveSiteId('') ?: ''
      } catch (Throwable ignored) {
        siteForQuery = ''
      }
    }
    urlStr = StudioToolOperationsSupport.rewriteStudioPreviewShellUrlForEngineFetch(urlStr, siteForQuery)
    String token = (toolPreviewToken ?: '').toString().trim()
    if (!token) token = ops.resolvedPreviewTokenFromRequest ?: ''
    if (!token) token = StudioToolOperationsSupport.readCrafterPreviewTokenFromServletRequest(ops.request) ?: ''
    if (!token) {
      return [
        ok     : false,
        action : 'get_preview_html',
        message:
          'Missing preview token: pass previewToken in tool arguments, send previewToken in the AI Assistant chat POST body, or ensure the browser sends the crafterPreview cookie on the chat request (HttpOnly cookies are read server-side).'
      ]
    }
    // Tool/UI may echo crafterPreview in the URL with literal '+' (base64); form-style query parsing treats '+' as
    // space and corrupts the ticket → HTTP 401. Always drop caller-supplied crafterPreview and append URLEncoder output.
    String u = StudioToolOperationsSupport.removeQueryParamsCaseInsensitive(urlStr, ['crafterPreview'])
    if (siteForQuery && !u.toLowerCase(Locale.ROOT).contains('craftersite=')) {
      u += (u.contains('?') ? '&' : '?') + 'crafterSite=' + URLEncoder.encode(siteForQuery, 'UTF-8')
    }
    u += (u.contains('?') ? '&' : '?') + 'crafterPreview=' + URLEncoder.encode(token, 'UTF-8')
    URI uri
    try {
      uri = new URI(u)
    } catch (Throwable t) {
      return [ok: false, action: 'get_preview_html', message: "Invalid URL: ${t.message}"]
    }
    if (!uri.scheme || (!'http'.equalsIgnoreCase(uri.scheme) && !'https'.equalsIgnoreCase(uri.scheme))) {
      return [ok: false, action: 'get_preview_html', message: 'url must use http or https']
    }
    String host = uri.host
    if (!host) {
      return [ok: false, action: 'get_preview_html', message: 'url must include a host name']
    }
    if (!hostAllowed(ops, host)) {
      return [
        ok     : false,
        action : 'get_preview_html',
        message:
          "Host '${host}' is not allowed for preview fetch. Allowed: this Studio server name (${ops.request?.getServerName()}), localhost, 127.0.0.1, [::1], or JVM aiassistant.preview.fetch.allowedHosts (comma-separated)."
      ]
    }
    Map<String, String> reqHeaders = new LinkedHashMap<>()
    reqHeaders.put('x-crafter-preview', token)
    String cookieHeader = buildCookieHeader(ops, token, siteForQuery)
    if (cookieHeader) {
      reqHeaders.put('Cookie', cookieHeader)
    }
    try {
      def ua = ops.request?.getHeader('User-Agent')?.toString()?.trim()
      if (ua) {
        reqHeaders.put('User-Agent', ua)
      }
      if (StudioAiPlatformSettings.propertyBoolean('aiassistant.preview.fetch.forwardAuthorization', false)) {
        def authz = ops.request?.getHeader('Authorization')?.toString()?.trim()
        if (authz) {
          reqHeaders.put('Authorization', authz)
        }
      }
      def ref = ops.request?.getHeader('Referer')?.toString()?.trim()
      if (!ref) {
        def ru = ops.request?.getRequestURL()
        if (ru != null) {
          ref = ru.toString().trim()
        }
      }
      if (ref) {
        reqHeaders.put('Referer', ref)
      }
    } catch (Throwable ignored) {
    }
    try {
      int maxChars = maxChars()
      def ex = StudioAiSandboxHttp.getText(uri, [
        connectTimeoutMs: 15_000,
        readTimeoutMs   : 45_000,
        maxRedirects    : 0,
        maxBodyChars    : maxChars,
        accept          : 'text/html,application/xhtml+xml;q=0.9,*/*;q=0.8',
        headers         : reqHeaders,
        ssrfCheck       : false
      ])
      if (ex.errorMessage && !ex.bodyText && ex.statusCode <= 0) {
        return [ok: false, action: 'get_preview_html', message: ex.errorMessage]
      }
      int status = ex.statusCode
      if (status == 401) {
        log.warn('fetchPreviewRenderedHtml HTTP 401 url={} crafterPreviewTokenChars={} outgoingCookieHeaderChars={}',
          u, token.length(), cookieHeader.length())
      }
      String ct = ex.contentType ?: ''
      if (status >= 300 && status < 400) {
        return [
          ok        : false,
          action    : 'get_preview_html',
          statusCode: status,
          message   : 'HTTP redirect — use the final preview URL (redirects are disabled for safety).',
          location  : ex.redirectLocation ?: StudioAiSandboxHttp.firstHeader(ex.responseHeaders, 'Location')
        ]
      }
      String body = ex.bodyText ?: ''
      return [
        action    : 'get_preview_html',
        ok        : status >= 200 && status < 300,
        statusCode: status,
        contentType: ct,
        charCount : body.length(),
        truncated : ex.truncated,
        html      : body,
        message   : status >= 200 && status < 300
          ? 'Fetched preview HTML.'
          : (status == 401
            ? 'HTTP 401 — Engine rejected preview fetch. The plugin sends x-crafter-preview, crafterPreview cookie/query (re-encoded), and crafterSite. Authorization is not forwarded unless JVM aiassistant.preview.fetch.forwardAuthorization=true. Ensure previewToken is in the AI Assistant chat/stream POST body and the author has an active XB preview session.'
            : "HTTP ${status} — body may be an error page.")
      ]
    } catch (Throwable t) {
      log.warn('fetchPreviewRenderedHtml failed url={}: {}', u, t.toString())
      return [
        ok     : false,
        action : 'get_preview_html',
        message: (t.message ?: t.toString())
      ]
    }
  }
}
