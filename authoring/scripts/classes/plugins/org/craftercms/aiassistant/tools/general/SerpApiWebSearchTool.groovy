package plugins.org.craftercms.aiassistant.tools.general

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.tools.http.OutboundHttpPolicy
import plugins.org.craftercms.aiassistant.tools.spi.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolContext
import plugins.org.craftercms.aiassistant.tools.spi.StudioAiToolSchemas

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
  private static final int DEFAULT_MAX_RESULTS = 10
  private static final String USER_AGENT =
    'Mozilla/5.0 (compatible; CrafterCMS-AI-Assistant/1.0; +https://craftercms.org)'

  @Override
  String wireName() { SerpApiWebSearchProjectSettings.WIRE }

  @Override
  String description() { ToolPrompts.getDESC_SERP_API_WEB_SEARCH() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.SERP_API_WEB_SEARCH }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    String query = parseQuery(input)
    Integer maxResults = parseMaxResults(input)
    Map cfg = ctx?.aiProjectToolCfg instanceof Map ? (Map) ctx.aiProjectToolCfg : [:]
    Map defaults = SerpApiWebSearchProjectSettings.resolveDefaults(cfg)
    Map overrides = paramOverridesFromToolInput(input, defaults)
    return runSearch(query, maxResults, defaults, overrides, cfg, ctx)
  }

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

  private Map runSearch(
    String query,
    Integer maxResultsOpt,
    Map siteDefaults,
    Map paramOverrides,
    Map cfg,
    StudioAiToolContext ctx
  ) {
    String apiKey = resolveApiKey(cfg, ctx)
    if (!apiKey?.trim()) {
      return [
        ok         : false,
        tool       : wireName(),
        query      : query,
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
    params.put('q', query)
    params.put('api_key', apiKey.trim())
    List<Map> results = fetchResults(params)
    if (results.isEmpty()) {
      return [
        ok         : false,
        tool       : wireName(),
        query      : query,
        message    : 'SerpAPI web search returned no organic results.',
        resultCount: 0,
        results    : []
      ]
    }
    return [
      ok         : true,
      tool       : wireName(),
      query      : query,
      resultCount: results.size(),
      results    : results
    ]
  }

  private static String resolveApiKey(Map cfg, StudioAiToolContext ctx) {
    if (ctx?.ops == null) {
      return ''
    }
    String resolved = plugins.org.craftercms.aiassistant.secrets.StudioAiAssistantSecretsService
      .resolveSecretKey(ctx.ops, SerpApiWebSearchProjectSettings.secretKeyId(cfg))
    String trimmed = (resolved ?: '').trim()
    if (trimmed && !trimmed.contains('${')) {
      return trimmed
    }
    return ''
  }

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

  private static boolean knownOptionalParam(String key) {
    return [
      'engine', 'googleDomain', 'gl', 'hl', 'location', 'num', 'start', 'device', 'safe',
      'tbm', 'tbs', 'nfpr', 'filter', 'lr', 'cr', 'uule'
    ].contains(key)
  }

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

  private List<Map> fetchResults(Map params) {
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
      if (status < 200 || status >= 300) {
        log.warn('SerpApiWebSearch HTTP {} q={}', status, params.q)
        return []
      }
      String body = ''
      InputStream is = conn.inputStream
      if (is != null) {
        body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).text
      }
      return parseJson(body, maxResults)
    } catch (Throwable t) {
      log.warn('SerpApiWebSearch failed: {}', t.message)
      return []
    } finally {
      try {
        conn?.disconnect()
      } catch (Throwable ignored) {
      }
    }
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

  private static List<Map> parseJson(String json, int maxResults) {
    List<Map> results = []
    if (!json?.trim() || maxResults < 1) {
      return results
    }
    try {
      Object parsed = new groovy.json.JsonSlurper().parseText(json)
      if (!(parsed instanceof Map)) {
        return results
      }
      Object organic = ((Map) parsed).get('organic_results')
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
      log.warn('SerpApiWebSearch JSON parse failed: {}', t.message)
    }
    return results
  }
}
