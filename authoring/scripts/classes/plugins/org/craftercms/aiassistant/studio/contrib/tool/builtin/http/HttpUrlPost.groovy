package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http

import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Outbound POST for {@link plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations.PostHttpUrlTool}. */
final class HttpUrlPost {

  private static final Logger log = LoggerFactory.getLogger(HttpUrlPost)

  private static final int MAX_REQUEST_BYTES = 1_048_576
  private static final Set<String> BLOCKED_REQUEST_HEADERS = [
    'host', 'connection', 'content-length', 'transfer-encoding', 'expect'
  ].collect { it.toLowerCase(Locale.ROOT) } as Set

  /**
   * Private constructor; not for direct use.
   */
private HttpUrlPost() {}

  /**
   * Post.
   * @param absoluteUrl Caller-supplied input.
   * @param postTypeRaw Caller-supplied input.
   * @param payloadRaw Caller-supplied input.
   * @param headersOpt Caller-supplied input.
   * @param maxCharsOpt Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map post(String absoluteUrl, String postTypeRaw, Object payloadRaw, Map headersOpt, Integer maxCharsOpt) {
    if (!OutboundHttpPolicy.globallyEnabled()) {
      return [
        ok     : false,
        action : 'post_http_url',
        message: 'HTTP outbound is disabled (JVM aiassistant.httpFetch.enabled=false).'
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
      return [ok: false, action: 'post_http_url', message: "Invalid URL: ${t.message}"]
    }
    String err0 = OutboundHttpPolicy.ssrfErrorForUri(start)
    if (err0) {
      return [ok: false, action: 'post_http_url', message: err0]
    }
    String postType = normalizePostType(postTypeRaw)
    if (!postType) {
      return [
        ok     : false,
        action : 'post_http_url',
        message: "Unsupported postType '${postTypeRaw}'. Use 'json' or 'form'."
      ]
    }
    def built = buildRequestBody(postType, payloadRaw)
    if (built.error) {
      return [ok: false, action: 'post_http_url', message: built.error]
    }
    byte[] bodyBytes = (byte[]) built.bytes
    String contentType = (String) built.contentType
    int maxChars = OutboundHttpPolicy.maxChars(maxCharsOpt)
    Map extraHeaders = collectExtraHeaders(headersOpt)
    try {
      def ex = StudioAiSandboxHttp.postBytes(
        start,
        bodyBytes,
        contentType,
        [
          connectTimeoutMs: 15_000,
          readTimeoutMs   : 60_000,
          maxRedirects    : 5,
          maxBodyChars    : maxChars,
          accept          : 'application/json,text/plain,*/*;q=0.8',
          userAgent       : 'CrafterCMS-AI-Assistant-Studio-Plugin/1.0 (+https://craftercms.org)',
          headers         : extraHeaders,
          ssrfCheck       : true
        ]
      )
      if (ex.errorMessage && !ex.bodyText && ex.statusCode <= 0) {
        return [ok: false, action: 'post_http_url', message: ex.errorMessage]
      }
      int status = ex.statusCode
      String body = ex.bodyText ?: ''
      String ct = ex.contentType ?: ''
      String finalUrl = ex.finalUrl ?: start.toString()
      return [
        action       : 'post_http_url',
        ok           : status >= 200 && status < 300,
        statusCode   : status,
        finalUrl     : finalUrl,
        contentType  : ct,
        requestType  : postType,
        requestBytes : bodyBytes.length,
        charCount    : body.length(),
        truncated    : ex.truncated,
        body         : body,
        redirects    : ex.redirectHops,
        message      : status >= 200 && status < 300
          ? 'POST completed; response body as UTF-8 text.'
          : (ex.errorMessage ?: "HTTP ${status} — body may be an error payload.")
      ]
    } catch (Throwable t) {
      log.warn('PostHttpUrl failed url={}: {}', absoluteUrl, t.toString())
      return [
        ok     : false,
        action : 'post_http_url',
        message: (t.message ?: t.toString())
      ]
    }
  }

  /**
   * Normalizes and validates post type; throws when required values are missing.
   * @param raw Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String normalizePostType(Object raw) {
    String t = (raw ?: 'json').toString().trim().toLowerCase(Locale.ROOT)
    if (!t) {
      t = 'json'
    }
    if (t in ['json', 'application/json', 'rest', 'rest_json', 'rest-json']) {
      return 'json'
    }
    if (t in ['form', 'form-urlencoded', 'application/x-www-form-urlencoded', 'x-www-form-urlencoded', 'urlencoded']) {
      return 'form'
    }
    return null
  }

  /**
   * Builds request body for tool or orchestration output.
   * @param postType Caller-supplied input.
   * @param payloadRaw Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map buildRequestBody(String postType, Object payloadRaw) {
    if (payloadRaw == null) {
      return [error: 'Missing required field: payload (object for JSON or flat key/value map for form)']
    }
    if ('json'.equals(postType)) {
      String json
      if (payloadRaw instanceof CharSequence) {
        json = payloadRaw.toString()
      } else {
        json = JsonOutput.toJson(payloadRaw)
      }
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8)
      if (bytes.length > MAX_REQUEST_BYTES) {
        return [error: "JSON payload exceeds ${MAX_REQUEST_BYTES} bytes"]
      }
      return [bytes: bytes, contentType: 'application/json; charset=utf-8']
    }
    if (payloadRaw instanceof CharSequence) {
      String raw = payloadRaw.toString()
      byte[] bytes = raw.getBytes(StandardCharsets.UTF_8)
      if (bytes.length > MAX_REQUEST_BYTES) {
        return [error: "Form payload exceeds ${MAX_REQUEST_BYTES} bytes"]
      }
      return [bytes: bytes, contentType: 'application/x-www-form-urlencoded; charset=utf-8']
    }
    if (!(payloadRaw instanceof Map)) {
      return [error: 'Form postType requires payload as an object map of field names to scalar values (or a form-urlencoded string)']
    }
    List<String> pairs = []
    for (def e : ((Map) payloadRaw).entrySet()) {
      String key = e.key?.toString()?.trim()
      if (!key) {
        continue
      }
      String val = scalarFormValue(e.value)
      pairs.add("${URLEncoder.encode(key, 'UTF-8')}=${URLEncoder.encode(val, 'UTF-8')}")
    }
    if (pairs.isEmpty()) {
      return [error: 'Form payload map is empty']
    }
    String encoded = pairs.join('&')
    byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8)
    if (bytes.length > MAX_REQUEST_BYTES) {
      return [error: "Form payload exceeds ${MAX_REQUEST_BYTES} bytes"]
    }
    return [bytes: bytes, contentType: 'application/x-www-form-urlencoded; charset=utf-8']
  }

  /**
   * Scalar form value.
   * @param v Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String scalarFormValue(Object v) {
    if (v == null) {
      return ''
    }
    if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean) {
      return v.toString()
    }
    return JsonOutput.toJson(v)
  }

  /**
   * Collects caller-supplied request headers (blocked names omitted).
   */
  private static Map collectExtraHeaders(Map headersOpt) {
    if (!(headersOpt instanceof Map) || headersOpt.isEmpty()) {
      return [:]
    }
    Map out = [:]
    for (def e : headersOpt.entrySet()) {
      String name = e.key?.toString()?.trim()
      if (!name) {
        continue
      }
      String lower = name.toLowerCase(Locale.ROOT)
      if (BLOCKED_REQUEST_HEADERS.contains(lower)) {
        continue
      }
      out[name] = e.value?.toString() ?: ''
    }
    return out
  }
}
