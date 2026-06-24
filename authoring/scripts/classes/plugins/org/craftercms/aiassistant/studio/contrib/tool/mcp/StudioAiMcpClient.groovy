package plugins.org.craftercms.aiassistant.studio.contrib.tool.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.config.StudioAiPlatformSettings
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsContext
import plugins.org.craftercms.aiassistant.studio.secrets.StudioAiSecretMacroResolver
import plugins.org.craftercms.aiassistant.studio.repository.StudioToolOperations
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal **MCP client** for the Streamable HTTP transport (JSON-RPC over HTTP POST).
 * <p>Registers extra function tools from site {@code tools.json} when {@code mcpEnabled} is JSON {@code true};
 * see {@link plugins.org.craftercms.aiassistant.studio.config.StudioAiAssistantProjectConfig#mcpClientEnabled}.
 * URLs use the same SSRF gate as {@link StudioToolOperations#fetchHttpUrl}.</p>
 */
final class StudioAiMcpClient {

  private static final Logger LOG = LoggerFactory.getLogger(StudioAiMcpClient.class)

  private static final int DEFAULT_READ_TIMEOUT_MS = 120_000

  /**
   * Max mcp response chars.
   * @return int result.
   */
  private static int maxMcpResponseChars() {
    int cap = StudioAiPlatformSettings.propertyInt('aiassistant.mcp.maxResponseChars', 500_000, 16_384, 2_000_000)
    return Math.min(2_000_000, Math.max(16_384, cap))
  }

  /**
   * One MCP session per server for a single Studio chat request.
   */
  static final class McpConnection {
    final String serverId
    final String baseUrl
    final Map<String, String> extraHeaders
    final int readTimeoutMs
    volatile String sessionId
    volatile String protocolVersion
    private final AtomicInteger rpcSeq = new AtomicInteger(1)

    /**
     * Private constructor; not for direct use.
     */
private McpConnection(String serverId, String baseUrl, Map<String, String> extraHeaders, int readTimeoutMs) {
      this.serverId = serverId
      this.baseUrl = baseUrl
      this.extraHeaders = extraHeaders != null ? new LinkedHashMap<>(extraHeaders) : [:]
      this.readTimeoutMs = readTimeoutMs
      this.protocolVersion = '2024-11-05'
    }

    int allocRpcId() {
      return rpcSeq.getAndIncrement()
    }

    Map toolsCall(String mcpToolName, Map arguments) {
      int id = allocRpcId()
      Map params = new LinkedHashMap<>()
      params.put('name', mcpToolName)
      params.put('arguments', arguments != null ? arguments : [:])
      boolean sess = sessionId != null && sessionId.toString().trim().length() > 0
      Map env = postJsonRpc('tools/call', params, id, sess)
      if (env.error != null) {
        return [
          ok     : false,
          mcp    : true,
          server : serverId,
          tool   : mcpToolName,
          message: (env.error instanceof Map) ? (((Map) env.error).get('message')?.toString() ?: 'MCP error') : 'MCP error',
          error  : env.error
        ]
      }
      return [
        ok    : true,
        mcp   : true,
        server: serverId,
        tool  : mcpToolName,
        result: env.result
      ]
    }

    /**
     * Post json rpc.
     * @param method Caller-supplied input.
     * @param params Studio or repository context for this call.
     * @param id Caller-supplied input.
     * @param sessionRequired Caller-supplied input.
     * @return Map payload for tools or orchestration.
     */
    Map postJsonRpc(String method, Map params, int id, boolean sessionRequired) {
      Map body = new LinkedHashMap<>()
      body.put('jsonrpc', '2.0')
      body.put('method', method)
      body.put('params', params != null ? params : [:])
      body.put('id', id)
      byte[] bytes = JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8)

      URI uri = new URI(baseUrl)
      String gate2 = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.OutboundHttpPolicy.validateUrl(uri.toString())
      if (gate2) {
        throw new IllegalStateException("MCP url blocked: ${gate2}")
      }

      String pv = (protocolVersion ?: '2024-11-05').toString().trim()
      String sidHdr = sessionId?.toString()?.trim()
      if (!sidHdr && sessionRequired) {
        throw new IllegalStateException('MCP session id missing for ' + method)
      }
      Map<String, String> reqHeaders = new LinkedHashMap<>(extraHeaders)
      reqHeaders.put('MCP-Protocol-Version', pv ?: '2024-11-05')
      if (sidHdr) {
        reqHeaders.put('Mcp-Session-Id', sidHdr)
      }

      def ex = StudioAiSandboxHttp.postBytes(
        uri,
        bytes,
        'application/json',
        [
          connectTimeoutMs: 15_000,
          readTimeoutMs   : readTimeoutMs,
          maxRedirects    : 0,
          accept          : 'application/json, text/event-stream',
          userAgent       : 'CrafterCMS-AI-Assistant-Studio-Plugin-MCP/1.0 (+https://craftercms.org)',
          headers         : reqHeaders,
          maxBodyChars    : maxMcpResponseChars(),
          ssrfCheck       : true
        ]
      )

      int code = ex.statusCode
      String ct = (ex.contentType ?: '').toString().toLowerCase(Locale.ROOT)
      String newSession = StudioAiSandboxHttp.firstHeader(ex.responseHeaders, 'Mcp-Session-Id')?.trim()
      String text = (ex.bodyText ?: '').toString()

      if (ct.contains('text/event-stream')) {
        return parseSseRpc(text, id, newSession)
      }
      if (!text?.trim()) {
        if (code == 202) {
          return [error: null, result: null, sessionId: newSession ?: sidHdr]
        }
        return [error: [message: "HTTP ${code} empty body"], result: null, sessionId: newSession]
      }
      Object parsed
      try {
        parsed = new JsonSlurper().parseText(text.trim())
      } catch (Throwable t) {
        return [error: [message: "Invalid JSON HTTP ${code}: ${t.message}"], result: null, sessionId: newSession]
      }
      if (!(parsed instanceof Map)) {
        return [error: [message: 'Non-object JSON response'], result: null, sessionId: newSession]
      }
      Map m = (Map) parsed
      if (m.containsKey('error') && m.get('error') != null) {
        return [error: m.get('error'), result: null, sessionId: newSession]
      }
      return [error: null, result: m.get('result'), sessionId: newSession]
    }

    /**
     * Post notification.
     * @param method Caller-supplied input.
     * @param params Studio or repository context for this call.
     */
    void postNotification(String method, Map params) {
      Map body = new LinkedHashMap<>()
      body.put('jsonrpc', '2.0')
      body.put('method', method)
      body.put('params', params != null ? params : [:])
      byte[] bytes = JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8)

      URI uri = new URI(baseUrl)
      String gate2 = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.OutboundHttpPolicy.validateUrl(uri.toString())
      if (gate2) {
        throw new IllegalStateException("MCP url blocked: ${gate2}")
      }

      Map<String, String> reqHeaders = new LinkedHashMap<>(extraHeaders)
      reqHeaders.put('MCP-Protocol-Version', (protocolVersion ?: '2024-11-05').toString().trim())
      String sidHdr = sessionId?.toString()?.trim()
      if (sidHdr) {
        reqHeaders.put('Mcp-Session-Id', sidHdr)
      }

      def ex = StudioAiSandboxHttp.postBytes(
        uri,
        bytes,
        'application/json',
        [
          connectTimeoutMs: 15_000,
          readTimeoutMs   : readTimeoutMs,
          maxRedirects    : 0,
          accept          : 'application/json, text/event-stream',
          userAgent       : 'CrafterCMS-AI-Assistant-Studio-Plugin-MCP/1.0 (+https://craftercms.org)',
          headers         : reqHeaders,
          maxBodyChars    : maxMcpResponseChars(),
          ssrfCheck       : true
        ]
      )
      int code = ex.statusCode
      if (code != 202 && (code < 200 || code >= 300)) {
        String t = (ex.bodyText ?: '').toString()
        LOG.warn('MCP notification {} returned HTTP {} body={}', method, code, t?.take(500))
      }
    }

    /**
     * Parse sse rpc.
     * @param text Caller-supplied input.
     * @param wantId Identifier for the target resource.
     * @param sessionFromHttp Caller-supplied input.
     * @return Map payload for tools or orchestration.
     */
    private static Map parseSseRpc(String text, int wantId, String sessionFromHttp) {
      if (!text?.trim()) {
        return [error: [message: 'empty SSE body'], result: null, sessionId: sessionFromHttp]
      }
      String lastSession = sessionFromHttp
      Map lastMatch = null
      for (String eventBlock : text.split('\n\n')) {
        if (!eventBlock?.trim()) {
          continue
        }
        StringBuilder dataJoin = new StringBuilder()
        for (String line : eventBlock.split('\n')) {
          if (line.startsWith('data:')) {
            if (dataJoin.length() > 0) {
              dataJoin.append('\n')
            }
            dataJoin.append(line.substring(5).trim())
          }
        }
        String payload = dataJoin.toString().trim()
        if (!payload || '[DONE]'.equalsIgnoreCase(payload)) {
          continue
        }
        try {
          Object parsed = new JsonSlurper().parseText(payload)
          if (parsed instanceof Map) {
            Map m = (Map) parsed
            if (!rpcIdMatches(m.get('id'), wantId)) {
              continue
            }
            lastMatch = m
          }
        } catch (Throwable ignored) {
        }
      }
      if (lastMatch == null) {
        return [error: [message: 'no matching JSON-RPC id in SSE stream'], result: null, sessionId: lastSession]
      }
      if (lastMatch.containsKey('error') && lastMatch.get('error') != null) {
        return [error: lastMatch.get('error'), result: null, sessionId: lastSession]
      }
      return [error: null, result: lastMatch.get('result'), sessionId: lastSession]
    }

    private static boolean rpcIdMatches(Object rid, int wantId) {
      if (rid == null) {
        return false
      }
      if (rid instanceof Number) {
        return ((Number) rid).intValue() == wantId
      }
      try {
        return Integer.parseInt(rid.toString().trim()) == wantId
      } catch (Throwable ignored) {
        return false
      }
    }
  }

  /**
   * Open session and list tools.
   * @param ops Caller-supplied input.
   * @param spec Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  static Map openSessionAndListTools(StudioToolOperations ops, Map spec) {
    String sid = spec?.id?.toString()?.trim() ?: ''
    String url = spec?.url?.toString()?.trim() ?: ''
    if (!sid || !url) {
      throw new IllegalArgumentException('mcpServers entry requires non-blank id and url')
    }
    String gate = plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.OutboundHttpPolicy.validateUrl(url)
    if (gate) {
      throw new IllegalStateException("MCP url blocked for server '${sid}': ${gate}")
    }
    if (ops != null && !plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.OutboundHttpPolicy.globallyEnabled()) {
      throw new IllegalStateException('MCP disabled: aiassistant.httpFetch.enabled=false')
    }
    int readTimeout = DEFAULT_READ_TIMEOUT_MS
    try {
      def r = spec.readTimeoutMs
      if (r instanceof Number) {
        readTimeout = ((Number) r).intValue()
      } else if (r != null) {
        readTimeout = Integer.parseInt(r.toString().trim())
      }
    } catch (Throwable ignored) {
      readTimeout = DEFAULT_READ_TIMEOUT_MS
    }
    readTimeout = Math.min(600_000, Math.max(10_000, readTimeout))

    Map<String, String> hdrs = normalizeHeaderMap(ops, spec.headers)
    McpConnection conn = new McpConnection(sid, url, hdrs, readTimeout)

    Map initParams = [
      protocolVersion: '2024-11-05',
      capabilities    : [tools: [:]],
      clientInfo      : [name: 'crafter-studio-aiassistant', version: '1.0.0']
    ]
    int initId = conn.allocRpcId()
    Map initEnv = conn.postJsonRpc('initialize', initParams, initId, false)
    if (initEnv.error != null) {
      throw new IllegalStateException("MCP initialize failed (${sid}): ${initEnv.error}")
    }
    Map initResult = initEnv.result instanceof Map ? (Map) initEnv.result : [:]
    String pv = initResult.get('protocolVersion')?.toString()?.trim()
    if (pv) {
      conn.protocolVersion = pv
    }
    if (initEnv.sessionId?.toString()?.trim()) {
      conn.sessionId = initEnv.sessionId.toString().trim()
    }
    conn.postNotification('notifications/initialized', [:])

    int listId = conn.allocRpcId()
    boolean listSess = conn.sessionId != null && conn.sessionId.toString().trim().length() > 0
    Map listEnv = conn.postJsonRpc('tools/list', [:], listId, listSess)
    if (listEnv.error != null) {
      throw new IllegalStateException("MCP tools/list failed (${sid}): ${listEnv.error}")
    }
    Map listResult = listEnv.result instanceof Map ? (Map) listEnv.result : [:]
    Object toolsRaw = listResult.get('tools')
    List<Map> tools = []
    if (toolsRaw instanceof List) {
      for (Object o : (List) toolsRaw) {
        if (o instanceof Map) {
          tools.add((Map) o)
        }
      }
    }
    return [connection: conn, tools: tools]
  }

  /**
   * Expands {@code ${env:…}}, {@code ${enc:…}}, and {@code ${secret:…}} for MCP header values.
   */
  static String expandEnvMacrosInString(String input) {
    String siteId = StudioAiAssistantSecretsContext.currentSiteId()
    Object ctx = StudioAiAssistantSecretsContext.currentApplicationContext()
    return StudioAiSecretMacroResolver.expand(siteId, ctx, input)
  }

  private static Map<String, String> normalizeHeaderMap(StudioToolOperations ops, Object headers) {
    if (!(headers instanceof Map)) {
      return [:]
    }
    String siteId = ops != null ? ops.resolveEffectiveSiteId('') : StudioAiAssistantSecretsContext.currentSiteId()
    Object ctx = ops != null ? ops.applicationContext : StudioAiAssistantSecretsContext.currentApplicationContext()
    Map<String, String> out = new LinkedHashMap<>()
    for (Map.Entry e : ((Map) headers).entrySet()) {
      String k = e.key != null ? e.key.toString().trim() : ''
      String v = e.value != null ? e.value.toString() : ''
      if (k) {
        out.put(k, StudioAiSecretMacroResolver.expand(siteId, ctx, v))
      }
    }
    return out
  }

  /** OpenAI function name: {@code mcp_<serverId>_<mcpToolName>}, max 64 chars. */
  static String wireToolName(String serverId, String mcpToolName) {
    String a = sanitizeToken(serverId)
    String b = sanitizeToken(mcpToolName)
    String base = "mcp_${a}_${b}"
    if (base.length() <= 64) {
      return base
    }
    return base.substring(0, 64)
  }

  /**
   * Sanitize token.
   * @param s Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String sanitizeToken(String s) {
    if (s == null) {
      return 'x'
    }
    String t = s.replaceAll('[^a-zA-Z0-9_]+', '_').replaceAll('_+', '_')
    t = t.replaceAll('^_|_$', '')
    return t.length() > 0 ? t : 'x'
  }
}
