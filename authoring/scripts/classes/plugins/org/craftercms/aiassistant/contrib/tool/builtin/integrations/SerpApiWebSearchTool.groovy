package plugins.org.craftercms.aiassistant.contrib.tool.builtin.integrations

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.contrib.tool.builtin.http.OutboundHttpPolicy
import plugins.org.craftercms.aiassistant.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolMaintainerObservability
import plugins.org.craftercms.aiassistant.spi.tool.StudioAiToolSchemas

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Google search via SerpAPI for open-web research. API key comes from site {@code secrets.json}
 * ({@link SerpApiWebSearchProjectSettings#SECRET_KEY}) only — not from a process-env bypass.
 * Site defaults and per-call overrides live in {@code tools.json} → {@code builtInToolSettings.SerpApiWebSearch}.
 */
class SerpApiWebSearchTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(SerpApiWebSearchTool)
  /** Per-thread Serp fetch diagnostics for maintainer session log (cleared after terminal tool-progress). */
  private static final ThreadLocal<Map> LAST_SERP_FETCH_DIAG = new ThreadLocal<>()
  private static final int DEFAULT_MAX_RESULTS = 10
  /** Full SerpAPI JSON body cap (parse path); do not truncate before {@link #parseJson}. */
  private static final int MAX_SERP_RESPONSE_CHARS = 512_000
  /** Maintainer log / HTTP-error snippets only. */
  private static final int MAINTAINER_RESPONSE_SNIPPET_CHARS = 400
  private static final String USER_AGENT =
    'Mozilla/5.0 (compatible; CrafterCMS-AI-Assistant/1.0; +https://craftercms.org)'

  @Override
  String wireName() { SerpApiWebSearchProjectSettings.WIRE }

  @Override
  String description() { ToolPrompts.getDESC_SERP_API_WEB_SEARCH() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.SERP_API_WEB_SEARCH }

  @Override
  Map maintainerObservability(String phase, Map input, Object toolResult, Throwable err) {
    if (!StudioAiToolMaintainerObservability.enabled()) {
      return [:]
    }
    Map out = new LinkedHashMap()
    String q = input?.query?.toString()?.trim() ?: input?.q?.toString()?.trim() ?: ''
    if (q) {
      out.query = q
    }
    Map diag = LAST_SERP_FETCH_DIAG.get()
    if (diag instanceof Map && !diag.isEmpty()) {
      out.putAll(diag)
    }
    if (toolResult instanceof Map) {
      Map tr = (Map) toolResult
      if (tr.containsKey('ok')) {
        out.ok = tr.ok
      }
      if (tr.resultCount != null) {
        out.resultCount = tr.resultCount
      }
      String msg = tr.message?.toString()?.trim()
      if (msg) {
        out.toolMessage = msg.length() > 300 ? msg.substring(0, 297) + '…' : msg
      }
    }
    if (err != null) {
      String em = err.message ?: err.toString()
      out.error = em.length() > 300 ? em.substring(0, 297) + '…' : em
    }
    if (!'start'.equals(phase)) {
      LAST_SERP_FETCH_DIAG.remove()
    }
    return out.isEmpty() ? [:] : Collections.unmodifiableMap(out)
  }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    String query = parseQuery(input)
    Integer maxResults = parseMaxResults(input)
    Map cfg = ctx?.aiProjectToolCfg instanceof Map ? (Map) ctx.aiProjectToolCfg : [:]
    Map defaults = SerpApiWebSearchProjectSettings.resolveDefaults(cfg)
    Map overrides = paramOverridesFromToolInput(input, defaults)
    return runSearch(query, maxResults, defaults, overrides, cfg, ctx)
  }

  /** Author-visible tool warn line; full Serp/Google detail stays in logs and maintainerObservability. */
  private static String authorMessageNoOrganicResults(String query) {
    String q = (query ?: '').trim() ?: '(empty query)'
    return 'No results for this query (' + q + ').'
  }

  /**
   * Elide query for author message.
   * @param query Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String elideQueryForAuthorMessage(String query) {
    String q = (query ?: '').trim()
    if (q.length() <= 160) {
      return q
    }
    return q.substring(0, 157) + '…'
  }

  /**
   * Parse query.
   * @param input Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String parseQuery(Map input) {
    String query = input?.query?.toString()?.trim()
    if (!query) {
      query = input?.q?.toString()?.trim()
    }
    if (!query) {
      throw new IllegalArgumentException('Missing required field: query')
    }
    return query
  }

  /**
   * Parse max results.
   * @param input Caller-supplied input.
   * @return Integer result.
   */
  private static Integer parseMaxResults(Map input) {
    if (input?.maxResults == null) {
      return null
    }
    try {
      return (input.maxResults instanceof Number) ?
        ((Number) input.maxResults).intValue() :
        Integer.parseInt(input.maxResults.toString().trim())
    } catch (Throwable ignored) {
      return null
    }
  }

  /**
   * Runs run search using Studio services and returns the tool payload.
   * @return Map payload for tools or orchestration.
   */
  private Map runSearch(
    String query,
    Integer maxResultsOpt,
    Map siteDefaults,
    Map paramOverrides,
    Map cfg,
    StudioAiToolContext ctx
  ) {
    try {
      return runSearchBody(query, maxResultsOpt, siteDefaults, paramOverrides, cfg, ctx)
    } finally {
      LAST_SERP_FETCH_DIAG.remove()
    }
  }

  /** SerpAPI fetch implementation; {@link #runSearch} clears {@link #LAST_SERP_FETCH_DIAG} in a finally block. */
  private Map runSearchBody(
    String query,
    Integer maxResultsOpt,
    Map siteDefaults,
    Map paramOverrides,
    Map cfg,
    StudioAiToolContext ctx
  ) {
    Map queryDisambig = OpenWebSearchQueryDisambiguation.disambiguate(query)
    String queryOriginal = queryDisambig.queryOriginal?.toString()?.trim() ?: query
    String querySent = queryDisambig.querySent?.toString()?.trim() ?: query
    boolean queryExpanded = Boolean.TRUE.equals(queryDisambig.queryExpanded)

    Map paramsPreview = new LinkedHashMap<>(siteDefaults instanceof Map ? siteDefaults : [:])
    if (paramOverrides instanceof Map) {
      paramsPreview.putAll(paramOverrides)
    }
    Map recencyOpt = OpenWebSearchQueryDisambiguation.optimizeForTbsRecency(querySent, paramsPreview)
    boolean queryRecencyOptimized = Boolean.TRUE.equals(recencyOpt.queryRecencyOptimized)
    if (queryRecencyOptimized) {
      querySent = recencyOpt.querySent?.toString()?.trim() ?: querySent
      log.info(
        'SerpApiWebSearch: stripped redundant month/year from query (tbs recency) original={} sent={}',
        queryOriginal,
        querySent
      )
    }

    String apiKey = resolveApiKey(cfg, ctx)
    if (!apiKey?.trim()) {
      return [
        ok         : false,
        tool       : wireName(),
        query      : queryOriginal,
        querySent  : querySent,
        message    : SerpApiWebSearchProjectSettings.missingApiKeyMessage(ctx),
        resultCount: 0,
        results    : []
      ]
    }
    Map params = new LinkedHashMap<>(siteDefaults instanceof Map ? siteDefaults : [:])
    if (paramOverrides instanceof Map) {
      for (Map.Entry e : paramOverrides.entrySet()) {
        if (e.value != null && e.value.toString().trim()) {
          params.put(e.key.toString(), e.value)
        }
      }
    }
    int maxResults = maxResults(maxResultsOpt, params)
    params.put('num', maxResults)
    params.put('q', querySent)
    params.put('api_key', apiKey.trim())
    Map fetchDiagSeed = LAST_SERP_FETCH_DIAG.get() instanceof Map ? new LinkedHashMap((Map) LAST_SERP_FETCH_DIAG.get()) : new LinkedHashMap()
    fetchDiagSeed.queryOriginal = queryOriginal
    fetchDiagSeed.querySent = querySent
    fetchDiagSeed.queryExpanded = queryExpanded
    fetchDiagSeed.queryRecencyOptimized = queryRecencyOptimized
    LAST_SERP_FETCH_DIAG.set(Collections.unmodifiableMap(fetchDiagSeed))
    List<Map> results = fetchResults(params)
    if (queryExpanded && results) {
      results = results.findAll { Map row ->
        !WebSearchResultTextUtil.skipHealthcareCmsResult(
          row?.url?.toString(),
          row?.title?.toString(),
          row?.snippet?.toString()
        )
      }
    }
    if (results.isEmpty()) {
      Map diag = LAST_SERP_FETCH_DIAG.get() instanceof Map ? (Map) LAST_SERP_FETCH_DIAG.get() : [:]
      String parseErr = diag.parseError?.toString()?.trim() ?: ''
      String serpErr = diag.serpApiError?.toString()?.trim() ?: ''
      String displayQuery = elideQueryForAuthorMessage(queryOriginal ?: querySent)
      String msg
      if (parseErr) {
        msg = 'SerpAPI response could not be parsed as JSON: ' + parseErr
        log.warn('SerpApiWebSearch JSON parse failed q={} sent={} err={}', queryOriginal, querySent, parseErr)
      } else {
        msg = authorMessageNoOrganicResults(displayQuery)
        log.warn(
          'SerpApiWebSearch no organic results q={} sent={} serpApiError={} httpStatus={} organicRaw={} tbs={}',
          queryOriginal,
          querySent,
          serpErr ?: '(none)',
          diag.httpStatus,
          diag.organicResultsRaw,
          (diag.serpParams?.tbs ?: '')
        )
      }
      return [
        ok         : false,
        tool       : wireName(),
        query      : queryOriginal,
        querySent  : querySent,
        message    : msg,
        serpApiError: serpErr ?: null,
        resultCount: 0,
        results    : []
      ]
    }
    return [
      ok         : true,
      tool       : wireName(),
      query      : queryOriginal,
      querySent  : querySent,
      resultCount: results.size(),
      results    : results
    ]
  }

  /**
   * Resolves api key from request and plugin context.
   * @param cfg Caller-supplied input.
   * @param ctx Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String resolveApiKey(Map cfg, StudioAiToolContext ctx) {
    if (ctx?.ops == null) {
      return ''
    }
    String resolved = plugins.org.craftercms.aiassistant.studio.secrets.StudioAiAssistantSecretsService
      .resolveSecretKey(ctx.ops, SerpApiWebSearchProjectSettings.secretKeyId(cfg))
    String trimmed = (resolved ?: '').trim()
    if (trimmed && !trimmed.contains('${')) {
      return trimmed
    }
    return ''
  }

  /**
   * Param overrides from tool input.
   * @param input Caller-supplied input.
   * @param defaults Caller-supplied input.
   * @return Map payload for tools or orchestration.
   */
  private static Map paramOverridesFromToolInput(Map input, Map defaults) {
    Map out = new LinkedHashMap<>()
    if (!(input instanceof Map)) {
      return out
    }
    Map<String, String> aliases = [
      google_domain: 'googleDomain',
      googleDomain : 'googleDomain'
    ]
    Set<String> reserved = ['query', 'q', 'maxResults', 'siteId', 'path', 'contentPath'] as Set
    for (Map.Entry e : input.entrySet()) {
      String key = e.key?.toString()?.trim()
      if (!key || reserved.contains(key)) {
        continue
      }
      String serpKey = aliases.get(key) ?: key
      if (defaults.containsKey(serpKey) || knownOptionalParam(serpKey)) {
        Object v = e.value
        if (v != null && v.toString().trim()) {
          out.put(serpKey, v)
        }
      }
    }
    return out
  }

  /**
   * Known optional param.
   * @param key Caller-supplied input.
   * @return True when the check succeeds.
   */
  private static boolean knownOptionalParam(String key) {
    return [
      'engine', 'googleDomain', 'gl', 'hl', 'location', 'num', 'start', 'device', 'safe',
      'tbm', 'tbs', 'nfpr', 'filter', 'lr', 'cr', 'uule'
    ].contains(key)
  }

  /**
   * Max results.
   * @param toolRequested Caller-supplied input.
   * @param params Studio or repository context for this call.
   * @return int result.
   */
  private static int maxResults(Integer toolRequested, Map params) {
    int fromTool = (toolRequested != null) ? toolRequested.intValue() : 0
    int fromCfg = 0
    Object n = params?.get('num')
    if (n instanceof Number) {
      fromCfg = ((Number) n).intValue()
    } else if (n != null) {
      try {
        fromCfg = Integer.parseInt(n.toString().trim())
      } catch (Throwable ignored) {
        fromCfg = 0
      }
    }
    int r = fromTool > 0 ? fromTool : (fromCfg > 0 ? fromCfg : DEFAULT_MAX_RESULTS)
    return Math.min(20, Math.max(1, r))
  }

  /**
   * Fetches results for tool use.
   * @param params Studio or repository context for this call.
   * @return List<Map> result.
   */
  private List<Map> fetchResults(Map params) {
    Map diag = LAST_SERP_FETCH_DIAG.get() instanceof Map ?
      new LinkedHashMap((Map) LAST_SERP_FETCH_DIAG.get()) :
      new LinkedHashMap()
    diag.serpParams = redactSerpParamsForMaintainer(params)
    LAST_SERP_FETCH_DIAG.set(Collections.unmodifiableMap(diag))

    int maxResults = maxResults(null, params)
    StringBuilder qs = new StringBuilder('https://serpapi.com/search.json?')
    boolean first = true
    for (Map.Entry<String, String> e : queryParams(params).entrySet()) {
      if (!first) {
        qs.append('&')
      }
      first = false
      qs.append(URLEncoder.encode(e.key, StandardCharsets.UTF_8.name()))
        .append('=')
        .append(URLEncoder.encode(e.value, StandardCharsets.UTF_8.name()))
    }
    URI uri = new URI(qs.toString())
    String hopErr = OutboundHttpPolicy.validateUrl(uri.toString())
    if (hopErr) {
      diag.ssrfBlocked = hopErr
      LAST_SERP_FETCH_DIAG.set(Collections.unmodifiableMap(diag))
      log.warn('SerpApiWebSearch blocked by SSRF policy: {}', hopErr)
      return []
    }
    HttpURLConnection conn = null
    try {
      conn = (HttpURLConnection) uri.toURL().openConnection()
      conn.setRequestMethod('GET')
      conn.setInstanceFollowRedirects(true)
      conn.setConnectTimeout(15000)
      conn.setReadTimeout(60_000)
      conn.setRequestProperty('Accept', 'application/json')
      conn.setRequestProperty('User-Agent', USER_AGENT)
      int status = conn.responseCode
      diag.httpStatus = status
      if (status < 200 || status >= 300) {
        String errBody = readHttpResponseBody(conn, true)
        if (errBody) {
          diag.responseSnippet = maintainerSnippet(errBody)
        }
        LAST_SERP_FETCH_DIAG.set(Collections.unmodifiableMap(diag))
        log.warn('SerpApiWebSearch HTTP {} q={}', status, params.q)
        return []
      }
      String body = readHttpResponseBody(conn, false)
      if (body.length() >= MAX_SERP_RESPONSE_CHARS) {
        diag.responseTruncated = true
      }
      Map parseMeta = [:]
      List<Map> hits = parseJson(body, maxResults, parseMeta)
      if (parseMeta.serpApiError) {
        diag.serpApiError = parseMeta.serpApiError
      }
      if (parseMeta.parseError) {
        diag.parseError = parseMeta.parseError
      }
      if (parseMeta.organicResultsRaw != null) {
        diag.organicResultsRaw = parseMeta.organicResultsRaw
      }
      diag.organicResultsReturned = hits.size()
      LAST_SERP_FETCH_DIAG.set(Collections.unmodifiableMap(diag))
      return hits
    } catch (Throwable t) {
      diag.fetchError = (t.message ?: t.toString())
      LAST_SERP_FETCH_DIAG.set(Collections.unmodifiableMap(diag))
      log.warn('SerpApiWebSearch failed: {}', t.message)
      return []
    } finally {
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {
      }
    }
  }

  /**
   * Loads http response body from configuration or input.
   * @param conn Caller-supplied input.
   * @param errorStream Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String readHttpResponseBody(HttpURLConnection conn, boolean errorStream) {
    if (conn == null) {
      return ''
    }
    InputStream is = errorStream ? conn.errorStream : conn.inputStream
    if (is == null) {
      return ''
    }
    try {
      return readUtf8(is, MAX_SERP_RESPONSE_CHARS)?.trim() ?: ''
    } catch (Throwable ignored) {
      return ''
    } finally {
      try {
        is.close()
      } catch (Throwable ignoredClose) {
      }
    }
  }

  /**
   * Maintainer snippet.
   * @param body Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String maintainerSnippet(String body) {
    if (!body?.trim()) {
      return ''
    }
    String s = body.trim()
    return s.length() > MAINTAINER_RESPONSE_SNIPPET_CHARS
      ? s.substring(0, MAINTAINER_RESPONSE_SNIPPET_CHARS - 1) + '…'
      : s
  }

  /**
   * Loads utf8 from configuration or input.
   * @param inStream Caller-supplied input.
   * @param maxChars Caller-supplied input.
   * @return Text result, or empty or null when unavailable.
   */
  private static String readUtf8(InputStream inStream, int maxChars) {
    if (inStream == null) {
      return ''
    }
    StringBuilder sb = new StringBuilder(Math.min(maxChars + 16, 8192))
    BufferedReader reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))
    char[] cbuf = new char[4096]
    int total = 0
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
        }
        break
      }
    }
    return sb.toString()
  }

  /**
   * Redact serp params for maintainer.
   * @param params Studio or repository context for this call.
   * @return Map payload for tools or orchestration.
   */
  private static Map redactSerpParamsForMaintainer(Map params) {
    Map out = new LinkedHashMap()
    if (!(params instanceof Map)) {
      return out
    }
    for (Map.Entry e : params.entrySet()) {
      String k = e.key?.toString()?.trim()
      if (!k) {
        continue
      }
      if ('api_key'.equalsIgnoreCase(k)) {
        out.put(k, '***')
        continue
      }
      Object v = e.value
      if (v != null && v.toString().trim()) {
        out.put(k, v.toString().trim())
      }
    }
    return out
  }

  private static Map<String, String> queryParams(Map params) {
    Map<String, String> out = new LinkedHashMap<>()
    Map<String, String> keyMap = [
      googleDomain: 'google_domain',
      api_key     : 'api_key',
      q           : 'q',
      engine      : 'engine',
      gl          : 'gl',
      hl          : 'hl',
      location    : 'location',
      num         : 'num',
      device      : 'device',
      safe        : 'safe',
      start       : 'start',
      tbm         : 'tbm',
      tbs         : 'tbs',
      nfpr        : 'nfpr',
      filter      : 'filter',
      lr          : 'lr',
      cr          : 'cr',
      uule        : 'uule'
    ]
    for (Map.Entry e : params.entrySet()) {
      String k = e.key?.toString()?.trim()
      if (!k) {
        continue
      }
      String serp = keyMap.get(k) ?: k
      Object v = e.value
      if (v == null) {
        continue
      }
      String s = v.toString().trim()
      if (s) {
        out.put(serp, s)
      }
    }
    return out
  }

  /**
   * Parse json.
   * @param json Caller-supplied input.
   * @param maxResults Caller-supplied input.
   * @param metaOut Caller-supplied input.
   * @return List<Map> result.
   */
  private static List<Map> parseJson(String json, int maxResults, Map metaOut) {
    List<Map> results = []
    if (!json?.trim() || maxResults < 1) {
      return results
    }
    try {
      Object parsed = new groovy.json.JsonSlurper().parseText(json)
      if (!(parsed instanceof Map)) {
        return results
      }
      Map root = (Map) parsed
      Object err = root.get('error')
      if (err != null && err.toString().trim()) {
        metaOut.serpApiError = err.toString().trim()
      }
      Object organic = root.get('organic_results')
      if (organic instanceof List) {
        metaOut.organicResultsRaw = ((List) organic).size()
      } else {
        metaOut.organicResultsRaw = 0
      }
      if (!(organic instanceof List)) {
        return results
      }
      int pos = 0
      for (Object row : (List) organic) {
        if (!(row instanceof Map) || results.size() >= maxResults) {
          break
        }
        Map hit = (Map) row
        String url = hit.link?.toString()?.trim() ?: hit.url?.toString()?.trim() ?: ''
        if (!url || WebSearchResultTextUtil.skipResultUrl(url)) {
          continue
        }
        pos++
        results.add([
          position: pos,
          title   : WebSearchResultTextUtil.stripHtml(hit.title?.toString() ?: ''),
          url     : url,
          snippet : WebSearchResultTextUtil.stripHtml(hit.snippet?.toString() ?: hit.description?.toString() ?: '')
        ])
      }
    } catch (Throwable t) {
      metaOut.parseError = t.message ?: t.toString()
      log.warn('SerpApiWebSearch JSON parse failed: {}', t.message)
    }
    return results
  }
}
