package plugins.org.craftercms.aiassistant.contrib.tool.builtin.http

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * CrafterQ chat API client — mirrors the public embed ({@code chat.crafterq.ai/embed.js}):
 * mint anonymous {@code X-CrafterQ-Chat-User} via {@code GET /v1/agents/{agentId}/chat_config},
 * then {@code POST /v1/chats?stream=true&agentId=…} (SSE).
 */
final class CrafterQChatApiClient {

  private static final Logger log = LoggerFactory.getLogger(CrafterQChatApiClient)
  private static final String HEADER_CHAT_USER = 'X-CrafterQ-Chat-User'
  private static final String USER_AGENT =
    'CrafterCMS-AI-Assistant-Studio-Plugin/1.0 (+https://craftercms.org)'
  private static final long CHAT_USER_REFRESH_SKEW_MS = 60_000L

  /**
   * {@code api.crafterq.ai} stream POST rejects payloads above ~1 KiB with HTTP 401 (not JWT expiry).
   * Keep merged {@code prompt} at or below this size (see {@link #assertStreamPromptWithinLimit}).
   */
  static final int MAX_STREAM_PROMPT_CHARS = 1_000

  private static final ConcurrentHashMap<String, ChatUserCacheEntry> CHAT_USER_CACHE =
    new ConcurrentHashMap<>()

  /**
   * Private constructor; not for direct use.
   */
private CrafterQChatApiClient() {}

  private static final class ChatUserCacheEntry {
    final String jwt
    final long expiresAtMs

    ChatUserCacheEntry(String jwt, long expiresAtMs) {
      this.jwt = jwt
      this.expiresAtMs = expiresAtMs
    }
  }

  /** True when {@code raw} looks like a CrafterQ anonymous chat JWT (three JWT segments). */
  static boolean looksLikeChatUserJwt(String raw) {
    String s = (raw ?: '').toString().trim()
    if (!s || s.length() < 20) {
      return false
    }
    if (s.regionMatches(true, 0, 'Bearer ', 0, 7)) {
      return false
    }
    if (s.startsWith('sk-') || s.startsWith('gsk_')) {
      return false
    }
    String[] parts = s.split('\\.')
    return parts.length >= 3 && parts[0]?.trim() && parts[1]?.trim() && parts[2]?.trim()
  }

  /** Accepts a CrafterQ agent UUID or a {@code chat.crafterq.ai/…} URL. */
  static String normalizeAgentId(String raw) {
    String s = (raw ?: '').toString().trim()
    if (!s) {
      return ''
    }
    int hash = s.indexOf('#')
    if (hash >= 0) {
      s = s.substring(0, hash).trim()
    }
    int q = s.indexOf('?')
    if (q >= 0) {
      s = s.substring(0, q).trim()
    }
    while (s.endsWith('/')) {
      s = s.substring(0, s.length() - 1).trim()
    }
    if (s.contains('/')) {
      int slash = s.lastIndexOf('/')
      if (slash >= 0 && slash < s.length() - 1) {
        s = s.substring(slash + 1).trim()
      }
    }
    s
  }

  /**
   * Resolves {@code X-CrafterQ-Chat-User} the same way the embed does: optional existing JWT on
   * {@code chat_config}, response header carries the canonical anonymous chat JWT (stored in
   * browser localStorage by the widget; cached in-process here).
   */
  static String ensureChatUser(String apiBase, String agentId, String existingJwt = null, boolean forceRefresh = false) {
    String agent = normalizeAgentId(agentId)
    if (!agent) {
      throw new IllegalArgumentException('Missing agentId')
    }
    String base = normalizeApiBase(apiBase)
    String cacheKey = "${base}|${agent}"
    if (!forceRefresh) {
      ChatUserCacheEntry hit = CHAT_USER_CACHE.get(cacheKey)
      long now = System.currentTimeMillis()
      if (hit?.jwt && hit.expiresAtMs > now + CHAT_USER_REFRESH_SKEW_MS) {
        return hit.jwt
      }
    } else {
      CHAT_USER_CACHE.remove(cacheKey)
    }
    String prior = (existingJwt ?: '').toString().trim()
    String jwt = fetchChatUserFromChatConfig(base, agent, prior)
    if (!jwt) {
      throw new RuntimeException(
        "CrafterQ did not return ${HEADER_CHAT_USER} from GET ${base}/v1/agents/${agent}/chat_config"
      )
    }
    long expMs = jwtExpMillis(jwt)
    CHAT_USER_CACHE.put(cacheKey, new ChatUserCacheEntry(jwt, expMs))
    return jwt
  }

  /**
   * Invalidate chat user cache.
   * @param apiBase Caller-supplied input.
   * @param agentId Identifier for the target resource.
   */
  static void invalidateChatUserCache(String apiBase, String agentId) {
    String agent = normalizeAgentId(agentId)
    if (!agent) {
      return
    }
    CHAT_USER_CACHE.remove("${normalizeApiBase(apiBase)}|${agent}")
  }

  /**
   * @param chatUserJwt {@code X-CrafterQ-Chat-User}; when blank, {@link #ensureChatUser} is called
   */
  static String streamChat(String apiBase, String agentId, String prompt, String chatUserJwt = null) {
    String agent = normalizeAgentId(agentId)
    if (!agent) {
      throw new IllegalArgumentException('Missing agentId')
    }
    String base = normalizeApiBase(apiBase)
    String chatUser = normalizeChatUserOverride(chatUserJwt, base, agent)
    try {
      return streamChatWithJwt(base, agent, prompt, chatUser)
    } catch (RuntimeException ex) {
      if (isUnauthorized(ex)) {
        log.warn(
          'CrafterQ chat HTTP 401 for agentId={} — refreshing anonymous JWT via chat_config (override ignored on retry)',
          agent
        )
        invalidateChatUserCache(base, agent)
        String refreshed = ensureChatUser(base, agent, null, true)
        return streamChatWithJwt(base, agent, prompt, refreshed)
      }
      throw ex
    }
  }

  /**
   * Normalizes and validates chat user override; throws when required values are missing.
   * @param chatUserJwt Caller-supplied input.
   * @param apiBase Caller-supplied input.
   * @param agentId Identifier for the target resource.
   * @return Text result, or empty or null when unavailable.
   */
  private static String normalizeChatUserOverride(String chatUserJwt, String apiBase, String agentId) {
    String chatUser = (chatUserJwt ?: '').toString().trim()
    if (chatUser && !looksLikeChatUserJwt(chatUser)) {
      log.warn(
        'CrafterQ ignoring X-CrafterQ-Chat-User override ({} chars, not a JWT); minting via GET …/chat_config',
        chatUser.length()
      )
      chatUser = ''
    }
    if (!chatUser) {
      chatUser = ensureChatUser(apiBase, agentId)
    }
    chatUser
  }

  /**
   * Stream chat with jwt.
   * @param base Caller-supplied input.
   * @param agent Caller-supplied input.
   * @param prompt Caller-supplied input.
   * @param chatUser Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String streamChatWithJwt(String base, String agent, String prompt, String chatUser) {
    String promptText = (prompt ?: '').toString()
    assertStreamPromptWithinLimit(promptText)
    String streamUrl =
      "${base}/v1/chats?stream=true&agentId=${URLEncoder.encode(agent, 'UTF-8')}"
    Map payload = [prompt: promptText]
    byte[] payloadBytes = JsonOutput.toJson(payload).getBytes(StandardCharsets.UTF_8)
    int maxAttempts = 2
    int attempt = 0
    while (attempt < maxAttempts) {
      attempt++
      HttpURLConnection conn = null
      InputStream inputStream = null
      try {
        conn = (HttpURLConnection) new URL(streamUrl).openConnection()
        conn.setRequestMethod('POST')
        conn.setDoOutput(true)
        conn.setInstanceFollowRedirects(false)
        conn.setFixedLengthStreamingMode(payloadBytes.length)
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.setRequestProperty('Accept', 'text/event-stream')
        conn.setRequestProperty('User-Agent', USER_AGENT)
        conn.setRequestProperty(HEADER_CHAT_USER, chatUser)
        conn.setConnectTimeout(15_000)
        conn.setReadTimeout(120_000)
        conn.getOutputStream().withCloseable { os -> os.write(payloadBytes) }
        int status = conn.getResponseCode()
        if (status >= 500 && attempt < maxAttempts) {
          log.warn('CrafterQ SSE HTTP {} (attempt {}/{}), retrying', status, attempt, maxAttempts)
          Thread.sleep(400)
          continue
        }
        if (status < 200 || status >= 300) {
          InputStream err = conn.getErrorStream()
          String errText = ''
          if (err != null) {
            err.withCloseable { errText = it.getText('UTF-8') ?: '' }
          }
          String sizeHint = streamPromptSizeHint(status, promptText.length())
          throw new RuntimeException(
            "HTTP ${status} calling ${streamUrl}: ${errText ?: conn.getResponseMessage()}${sizeHint}"
          )
        }
        inputStream = conn.getInputStream()
        StringBuilder acc = new StringBuilder()
        JsonSlurper slurper = new JsonSlurper()
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
        String line
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith('data:')) {
            continue
          }
          String data = line.substring(5).trim()
          if (!data) {
            continue
          }
          try {
            def obj = slurper.parseText(data)
            def metaObj = obj?.metadata ?: [:]
            def meta = (metaObj instanceof Map) ? (metaObj as Map) : [:]
            boolean completed = (meta?.completed != null) ? meta.completed.asBoolean() : false
            String chunk = extractAssistantText(obj)
            if (chunk) {
              acc.append(chunk)
            }
            if (completed) {
              break
            }
          } catch (Exception parseEx) {
            log.warn('CrafterQ SSE parse failed: {}', parseEx.toString())
          }
        }
        return acc.toString()
      } catch (SocketTimeoutException ste) {
        if (attempt >= maxAttempts) {
          throw new RuntimeException("Request timed out calling ${streamUrl}", ste)
        }
      } finally {
        try {
          inputStream?.close()
        } catch (Throwable ignored) {
        }
        try {
          conn?.disconnect()
        } catch (Throwable ignored2) {
        }
      }
    }
    throw new RuntimeException("POST failed after retries calling ${streamUrl}")
  }

  /**
   * Fetches chat user from chat config for tool use.
   * @param base Caller-supplied input.
   * @param agent Caller-supplied input.
   * @param existingJwt Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String fetchChatUserFromChatConfig(String base, String agent, String existingJwt) {
    String configUrl = "${base}/v1/agents/${URLEncoder.encode(agent, 'UTF-8')}/chat_config"
    HttpURLConnection conn = null
    try {
      conn = (HttpURLConnection) new URL(configUrl).openConnection()
      conn.setRequestMethod('GET')
      conn.setInstanceFollowRedirects(false)
      conn.setRequestProperty('Accept', 'application/json')
      conn.setRequestProperty('User-Agent', USER_AGENT)
      if (looksLikeChatUserJwt(existingJwt)) {
        conn.setRequestProperty(HEADER_CHAT_USER, existingJwt)
      }
      conn.setConnectTimeout(15_000)
      conn.setReadTimeout(30_000)
      int status = conn.getResponseCode()
      String jwt = readChatUserHeader(conn)
      if (status < 200 || status >= 300) {
        InputStream err = conn.getErrorStream()
        String errText = ''
        if (err != null) {
          err.withCloseable { errText = it.getText('UTF-8') ?: '' }
        }
        throw new RuntimeException("HTTP ${status} calling ${configUrl}: ${errText ?: conn.getResponseMessage()}")
      }
      // Consume body so connection can complete (embed reads JSON config; we only need the header).
      conn.inputStream?.withCloseable { InputStream is -> is.readAllBytes() }
      return jwt
    } finally {
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Loads chat user header from configuration or input.
   * @param conn Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String readChatUserHeader(HttpURLConnection conn) {
    if (conn == null) {
      return ''
    }
    try {
      String direct = conn.getHeaderField(HEADER_CHAT_USER)?.toString()?.trim()
      if (looksLikeChatUserJwt(direct)) {
        return direct
      }
    } catch (Throwable ignored) {
    }
    Map<String, List<String>> fields = conn.getHeaderFields()
    if (!fields) {
      return ''
    }
    for (Map.Entry<String, List<String>> e : fields.entrySet()) {
      if (e.key == null) {
        continue
      }
      if (!HEADER_CHAT_USER.equalsIgnoreCase(e.key.trim())) {
        continue
      }
      for (String v : e.value) {
        String s = (v ?: '').toString().trim()
        if (looksLikeChatUserJwt(s)) {
          return s
        }
      }
    }
    return ''
  }

  /**
   * Jwt exp millis.
   * @param jwt Caller-supplied input.
   * @return long result.
   */
  private static long jwtExpMillis(String jwt) {
    try {
      String[] parts = jwt.split('\\.')
      if (parts.length < 2) {
        return System.currentTimeMillis() + 3_600_000L
      }
      String payload = parts[1]
      int pad = (4 - (payload.length() % 4)) % 4
      if (pad > 0) {
        payload = payload + ('=' * pad)
      }
      payload = payload.replace('-', '+').replace('_', '/')
      byte[] decoded = Base64.decoder.decode(payload)
      def json = new JsonSlurper().parseText(new String(decoded, StandardCharsets.UTF_8))
      def exp = json?.exp
      if (exp instanceof Number) {
        return ((Number) exp).longValue() * 1000L
      }
    } catch (Throwable ignored) {
    }
    return System.currentTimeMillis() + 3_600_000L
  }

  /**
   * True when unauthorized.
   * @param ex Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean isUnauthorized(RuntimeException ex) {
    String msg = (ex?.message ?: '').toString()
    return msg.contains('HTTP 401') && !msg.contains('prompt too long')
  }

  /**
   * Assert stream prompt within limit.
   * @param prompt Caller-supplied input.
   */
  static void assertStreamPromptWithinLimit(String prompt) {
    int len = (prompt ?: '').length()
    if (len > MAX_STREAM_PROMPT_CHARS) {
      throw new IllegalArgumentException(
        "CrafterQ prompt too long (${len} chars; max ${MAX_STREAM_PROMPT_CHARS}). " +
          'Shorten instructions or pass a shorter draft excerpt.'
      )
    }
  }

  /**
   * Stream prompt size hint.
   * @param httpStatus Caller-supplied input.
   * @param promptChars Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String streamPromptSizeHint(int httpStatus, int promptChars) {
    if (httpStatus != 401) {
      return ''
    }
    if (promptChars > MAX_STREAM_PROMPT_CHARS) {
      return " (prompt ${promptChars} chars exceeds CrafterQ limit ~${MAX_STREAM_PROMPT_CHARS}; not an auth failure)"
    }
    return ''
  }

  /**
   * Normalizes and validates api base; throws when required values are missing.
   * @param apiBase Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String normalizeApiBase(String apiBase) {
    String base = (apiBase ?: '').toString().trim()
    if (!base) {
      throw new IllegalArgumentException('Missing apiBase')
    }
    if (base.endsWith('/')) {
      base = base.substring(0, base.length() - 1)
    }
    base
  }

  /**
   * Extracts assistant text from repository XML or related text.
   * @param obj Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String extractAssistantText(def obj) {
    if (obj == null) {
      return ''
    }
    if (obj instanceof String) {
      return obj
    }
    if (obj instanceof Map) {
      Map m = (Map) obj
      def t = m?.text ?: m?.content ?: m?.delta
      if (t != null && t.toString().trim()) {
        return t.toString()
      }
      t = m?.message
      if (t instanceof Map) {
        return (t?.content ?: t?.text ?: t?.delta ?: '').toString()
      }
      if (t instanceof String) {
        return t
      }
      def choices = m?.choices
      if (choices instanceof List && !choices.isEmpty()) {
        def first = choices.get(0)
        if (first instanceof Map) {
          def delta = first?.delta
          if (delta instanceof Map) {
            return (delta?.content ?: delta?.text ?: '').toString()
          }
          if (delta instanceof String) {
            return delta
          }
        }
      }
      def data = m?.data
      if (data instanceof Map) {
        return (data?.text ?: data?.content ?: '').toString()
      }
      if (data instanceof String) {
        return data
      }
    }
    return ''
  }
}
