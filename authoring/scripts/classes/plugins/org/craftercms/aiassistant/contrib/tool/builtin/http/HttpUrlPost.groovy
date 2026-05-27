package plugins.org.craftercms.aiassistant.contrib.tool.builtin.http

import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Outbound POST for {@link plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations.PostHttpUrlTool}. */
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
    final int maxRedirects = 5
    int redirectCount = 0
    URI currentUri = start
    HttpURLConnection conn = null
    InputStream inStream = null
    try {
      while (true) {
        String hopErr = OutboundHttpPolicy.ssrfErrorForUri(currentUri)
        if (hopErr) {
          return [ok: false, action: 'post_http_url', message: hopErr]
        }
        URL url = currentUri.toURL()
        conn = (HttpURLConnection) url.openConnection()
        conn.setRequestMethod('POST')
        conn.setInstanceFollowRedirects(false)
        conn.setDoOutput(true)
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(60_000)
        conn.setRequestProperty('Content-Type', contentType)
        conn.setRequestProperty('Accept', 'application/json,text/plain,*/*;q=0.8')
        conn.setRequestProperty('Accept-Encoding', 'identity')
        conn.setRequestProperty('User-Agent', 'CrafterCMS-AI-Assistant-Studio-Plugin/1.0 (+https://craftercms.org)')
        applyExtraHeaders(conn, headersOpt)
        conn.setFixedLengthStreamingMode(bodyBytes.length)
        OutputStream out = conn.getOutputStream()
        out.write(bodyBytes)
        out.flush()
        out.close()
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
              action    : 'post_http_url',
              statusCode: status,
              message   : "HTTP ${status} redirect without Location header."
            ]
          }
          try {
            currentUri = currentUri.resolve(new URI(loc))
          } catch (Throwable t) {
            return [ok: false, action: 'post_http_url', message: "Invalid redirect Location: ${t.message}"]
          }
          redirectCount++
          continue
        }
        String ct = (conn.getContentType() ?: '').toString()
        if (status >= 300 && status < 400) {
          return [
            ok        : false,
            action    : 'post_http_url',
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
        return [
          action        : 'post_http_url',
          ok            : status >= 200 && status < 300,
          statusCode    : status,
          finalUrl      : finalUrl,
          contentType   : ct,
          requestType   : postType,
          requestBytes  : bodyBytes.length,
          charCount     : body.length(),
          truncated     : truncated,
          body          : body,
          redirects     : redirectCount,
          message       : status >= 200 && status < 300
            ? 'POST completed; response body as UTF-8 text.'
            : "HTTP ${status} — body may be an error payload."
        ]
      }
    } catch (Throwable t) {
      log.warn('PostHttpUrl failed url={}: {}', absoluteUrl, t.toString())
      return [
        ok     : false,
        action : 'post_http_url',
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
   * Applies extra headers to repository content or orchestration state.
   * @param conn Caller-supplied input.
   * @param headersOpt Caller-supplied input.
   */
  private static void applyExtraHeaders(HttpURLConnection conn, Map headersOpt) {
    if (!(headersOpt instanceof Map) || headersOpt.isEmpty()) {
      return
    }
    for (def e : headersOpt.entrySet()) {
      String name = e.key?.toString()?.trim()
      if (!name) {
        continue
      }
      String lower = name.toLowerCase(Locale.ROOT)
      if (BLOCKED_REQUEST_HEADERS.contains(lower)) {
        continue
      }
      String val = e.value?.toString() ?: ''
      conn.setRequestProperty(name, val)
    }
  }
}
