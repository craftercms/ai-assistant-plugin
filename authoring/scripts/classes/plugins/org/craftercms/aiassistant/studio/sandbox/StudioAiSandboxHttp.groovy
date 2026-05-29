package plugins.org.craftercms.aiassistant.studio.sandbox

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.OutboundHttpPolicy

import java.nio.charset.StandardCharsets

/**
 * Outbound HTTP without {@code java.net.URL#openConnection} / {@code HttpURLConnection}
 * (blocked by Studio Groovy sandbox blacklist).
 * Uses Spring {@link RestClient} + {@link SimpleClientHttpRequestFactory} (Apache 2.0, on Studio classpath).
 */
final class StudioAiSandboxHttp {

  static final String DEFAULT_USER_AGENT =
    'CrafterCMS-AI-Assistant-Studio-Plugin/1.0 (+https://craftercms.org)'

  private StudioAiSandboxHttp() {}

  /**
   * Mutable result of one hop or a completed redirect chain.
   */
  static final class ExchangeResult {
    boolean ok = true
    int statusCode = 0
    String bodyText = ''
    byte[] bodyBytes = new byte[0]
    boolean truncated = false
    String contentType = ''
    String finalUrl = ''
    String redirectLocation = ''
    String errorMessage = ''
    HttpHeaders responseHeaders
    int redirectHops = 0

    static ExchangeResult policyFailure(String message) {
      def r = new ExchangeResult()
      r.ok = false
      r.errorMessage = message ?: 'request denied'
      return r
    }
  }

  static SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
    def rf = new SimpleClientHttpRequestFactory()
    rf.setConnectTimeout(Math.max(1_000, connectTimeoutMs))
    rf.setReadTimeout(Math.max(1_000, readTimeoutMs))
    return rf
  }

  static RestClient restClient(int connectTimeoutMs, int readTimeoutMs) {
    return RestClient.builder()
      .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
      .build()
  }

  /**
   * @param cfg optional keys: connectTimeoutMs, readTimeoutMs, maxRedirects, maxBodyChars (text truncation),
   *   accept, userAgent, headers (Map), authorization (Bearer value without prefix), readAsText (default true),
   *   ssrfCheck (default true when OutboundHttpPolicy applies)
   */
  static ExchangeResult exchangeWithRedirects(
    HttpMethod method,
    URI startUri,
    byte[] requestBody,
    String requestContentType,
    Map cfg = null
  ) {
    Map c = cfg ?: [:]
    int connectMs = c.connectTimeoutMs != null ? (c.connectTimeoutMs as int) : 15_000
    int readMs = c.readTimeoutMs != null ? (c.readTimeoutMs as int) : 60_000
    int maxRedirects = c.maxRedirects != null ? (c.maxRedirects as int) : 5
    boolean ssrfCheck = c.ssrfCheck == null ? true : (c.ssrfCheck as boolean)
    RestClient client = restClient(connectMs, readMs)
    URI current = startUri
    int hops = 0
    for (int attempt = 0; attempt <= maxRedirects; attempt++) {
      if (ssrfCheck) {
        String hopErr = OutboundHttpPolicy.ssrfErrorForUri(current)
        if (hopErr) {
          return ExchangeResult.policyFailure(hopErr)
        }
      }
      ExchangeResult hop = singleHop(client, method, current, requestBody, requestContentType, c)
      hop.redirectHops = hops
      if (hop.errorMessage) {
        return hop
      }
      if (hop.redirectLocation && hop.statusCode >= 300 && hop.statusCode < 400) {
        if (hops >= maxRedirects) {
          hop.ok = false
          hop.errorMessage = "Exceeded maximum of ${maxRedirects} redirect hops."
          return hop
        }
        try {
          current = current.resolve(new URI(hop.redirectLocation.trim()))
          hops++
          continue
        } catch (Throwable t) {
          return ExchangeResult.policyFailure("Invalid redirect Location: ${t.message}")
        }
      }
      hop.redirectHops = hops
      return hop
    }
    return ExchangeResult.policyFailure("Exceeded maximum of ${maxRedirects} redirect hops.")
  }

  /** UTF-8 text body; truncates when {@code maxBodyChars} is set in cfg. */
  static ExchangeResult getText(URI uri, Map cfg = null) {
    return exchangeWithRedirects(HttpMethod.GET, uri, null, null, cfg)
  }

  static ExchangeResult postBytes(URI uri, byte[] body, String contentType, Map cfg = null) {
    return exchangeWithRedirects(HttpMethod.POST, uri, body, contentType, cfg)
  }

  /** Full response bytes (no charset truncation); {@code maxBodyChars} in cfg is ignored. */
  static ExchangeResult getBytes(URI uri, Map cfg = null) {
    Map c = new LinkedHashMap(cfg ?: [:])
    c.readAsText = false
    c.maxBodyChars = null
    return exchangeWithRedirects(HttpMethod.GET, uri, null, null, c)
  }

  static String firstHeader(HttpHeaders headers, String name) {
    if (headers == null || !name) {
      return ''
    }
    return headers.getFirst(name) ?: ''
  }

  private static ExchangeResult singleHop(
    RestClient client,
    HttpMethod method,
    URI uri,
    byte[] requestBody,
    String requestContentType,
    Map cfg
  ) {
    String accept = (cfg?.accept ?: '*/*').toString()
    String userAgent = (cfg?.userAgent ?: DEFAULT_USER_AGENT).toString()
    Map extraHeaders = (cfg?.headers instanceof Map) ? (Map) cfg.headers : [:]
    String auth = cfg?.authorization?.toString()?.trim()
    boolean readAsText = cfg?.readAsText == null ? true : (cfg.readAsText as boolean)
    Integer maxChars = cfg?.maxBodyChars != null ? (cfg.maxBodyChars as Integer) : null

    def spec = client.method(method).uri(uri)
    spec = spec.headers { h ->
      h.set(HttpHeaders.USER_AGENT, userAgent)
      h.set(HttpHeaders.ACCEPT_ENCODING, 'identity')
      if (accept) {
        h.set(HttpHeaders.ACCEPT, accept)
      }
      if (auth) {
        h.set(HttpHeaders.AUTHORIZATION, auth.startsWith('Bearer ') ? auth : ('Bearer ' + auth))
      }
      extraHeaders.each { k, v ->
        if (k != null && v != null) {
          h.set(k.toString(), v.toString())
        }
      }
      if (requestBody != null && requestContentType) {
        h.set(HttpHeaders.CONTENT_TYPE, requestContentType)
      }
    }
    if (requestBody != null) {
      spec = spec.body(requestBody)
    }

    ExchangeResult out = new ExchangeResult()
    try {
      return spec.exchange { req, resp ->
        out.statusCode = resp.statusCode.value()
        out.responseHeaders = resp.headers
        out.contentType = resp.headers.getContentType()?.toString() ?: ''
        // Groovy req.uri fails on SimpleClientHttpRequest (use getURI() via URI property, or the hop URI).
        out.finalUrl = uri.toString()
        if (out.statusCode >= 300 && out.statusCode < 400) {
          out.redirectLocation = resp.headers.getFirst(HttpHeaders.LOCATION) ?: ''
          out.bodyText = ''
          out.bodyBytes = new byte[0]
          return out
        }
        byte[] bytes
        try {
          def is = resp.body
          bytes = is != null ? is.readAllBytes() : new byte[0]
        } finally {
          try {
            resp.close()
          } catch (Throwable ignored) {
          }
        }
        out.bodyBytes = bytes
        if (readAsText) {
          String full = new String(bytes, StandardCharsets.UTF_8)
          int cap = maxChars != null && maxChars > 0 ? maxChars : full.length()
          if (full.length() > cap) {
            out.truncated = true
            out.bodyText = full.substring(0, cap)
          } else {
            out.bodyText = full
          }
        }
        out.ok = out.statusCode >= 200 && out.statusCode < 300
        return out
      }
    } catch (Throwable t) {
      out.ok = false
      out.errorMessage = t.message ?: t.toString()
      return out
    }
  }
}
