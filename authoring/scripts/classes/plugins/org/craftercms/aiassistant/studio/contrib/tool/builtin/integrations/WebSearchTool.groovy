package plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.integrations

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.studio.engine.prompt.ToolPrompts
import plugins.org.craftercms.aiassistant.studio.contrib.tool.builtin.http.OutboundHttpPolicy
import plugins.org.craftercms.aiassistant.studio.sandbox.StudioAiSandboxHttp
import plugins.org.craftercms.aiassistant.studio.spi.tool.AbstractStudioAiTool
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolContext
import plugins.org.craftercms.aiassistant.studio.spi.tool.StudioAiToolSchemas

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Open-web search via DuckDuckGo HTML (no API keys). Studio must reach the public internet.
 */
class WebSearchTool extends AbstractStudioAiTool {

  private static final Logger log = LoggerFactory.getLogger(WebSearchTool)
  private static final int DEFAULT_MAX_RESULTS = 8
  private static final String USER_AGENT =
    'Mozilla/5.0 (compatible; CrafterCMS-AI-Assistant/1.0; +https://craftercms.org)'

  @Override
  String wireName() { 'WebSearch' }

  @Override
  String description() { ToolPrompts.getDESC_WEB_SEARCH() }

  @Override
  String inputSchemaJson() { StudioAiToolSchemas.WEB_SEARCH }

  @Override
  Map execute(Map input, StudioAiToolContext ctx) {
    String query = parseQuery(input)
    Map queryDisambig = OpenWebSearchQueryDisambiguation.disambiguate(query)
    String queryOriginal = queryDisambig.queryOriginal?.toString()?.trim() ?: query
    String querySent = queryDisambig.querySent?.toString()?.trim() ?: query
    int maxResults = maxResults(parseMaxResults(input))
    Map searchOutcome = duckDuckGoResults(querySent, maxResults)
    List<Map> results = (searchOutcome.results instanceof List) ? (List<Map>) searchOutcome.results : []
    if (Boolean.TRUE.equals(queryDisambig.queryExpanded) && results) {
      results = results.findAll { Map row ->
        !WebSearchResultTextUtil.skipHealthcareCmsResult(
          row?.url?.toString(),
          row?.title?.toString(),
          row?.snippet?.toString()
        )
      }
    }
    if (results.isEmpty()) {
      if (Boolean.TRUE.equals(searchOutcome.reachError)) {
        log.error(
          'WebSearch returned no results after search-service reach failure query={} detail={}',
          queryOriginal,
          searchOutcome.reachErrorDetail ?: 'see prior WebSearch DuckDuckGo errors'
        )
      }
      return [
        ok         : false,
        tool       : wireName(),
        query      : queryOriginal,
        querySent  : querySent,
        message    : 'Web search returned no results (the search service may be unreachable or blocked from Studio).',
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
   * Max results.
   * @param toolRequested Caller-supplied input.
   * @return int result.
   */
  private static int maxResults(Integer toolRequested) {
    int r = (toolRequested != null) ? toolRequested.intValue() : DEFAULT_MAX_RESULTS
    return (int) Math.min(15, Math.max(1, r))
  }

  /**
   * Duck duck go results.
   * @param q Caller-supplied input.
   * @param maxResults Caller-supplied input.
   * @return Map with {@code results} (List) and {@code reachError} when HTTP/policy/I/O failed.
   */
  private Map duckDuckGoResults(String q, int maxResults) {
    Map lite = duckDuckGoPost('https://lite.duckduckgo.com/lite/', q, maxResults, true)
    List<Map> liteResults = (lite.results instanceof List) ? (List<Map>) lite.results : []
    if (!liteResults.isEmpty()) {
      return lite
    }
    Map html = duckDuckGoPost('https://html.duckduckgo.com/html/', q, maxResults, false)
    return [
      results         : html.results instanceof List ? html.results : [],
      reachError      : Boolean.TRUE.equals(lite.reachError) || Boolean.TRUE.equals(html.reachError),
      reachErrorDetail: (lite.reachErrorDetail ?: html.reachErrorDetail ?: '').toString().trim()
    ]
  }

  /**
   * Duck duck go post.
   * @param endpoint Caller-supplied input.
   * @param q Caller-supplied input.
   * @param maxResults Caller-supplied input.
   * @param liteParser Caller-supplied input.
   * @return Map with {@code results}, {@code reachError}, {@code reachErrorDetail}.
   */
  private Map duckDuckGoPost(String endpoint, String q, int maxResults, boolean liteParser) {
    URI uri = new URI(endpoint)
    String hopErr = OutboundHttpPolicy.validateUrl(uri.toString())
    if (hopErr) {
      log.error('WebSearch DuckDuckGo blocked by SSRF policy endpoint={}: {}', endpoint, hopErr)
      return [results: [], reachError: true, reachErrorDetail: hopErr]
    }
    String body = 'q=' + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
    try {
      def ex = StudioAiSandboxHttp.postBytes(
        uri,
        body.getBytes(StandardCharsets.UTF_8),
        'application/x-www-form-urlencoded; charset=UTF-8',
        [
          userAgent       : USER_AGENT,
          accept          : 'text/html,application/xhtml+xml',
          connectTimeoutMs: 15_000,
          readTimeoutMs   : 60_000,
          maxRedirects    : 5,
          ssrfCheck       : false
        ]
      )
      if (ex.errorMessage) {
        String detail = ex.errorMessage.toString()
        log.error('WebSearch DuckDuckGo I/O failed endpoint={} query={}: {}', endpoint, q, detail)
        return [results: [], reachError: true, reachErrorDetail: detail]
      }
      int status = ex.statusCode
      if (status < 200 || status >= 300) {
        String detail = "HTTP ${status}"
        log.error('WebSearch DuckDuckGo HTTP {} endpoint={} query={}', status, endpoint, q)
        return [results: [], reachError: true, reachErrorDetail: detail]
      }
      String html = ex.bodyText ?: ''
      List<Map> parsed = liteParser ?
        parseDuckDuckGoLiteHtml(html, maxResults) :
        parseDuckDuckGoHtml(html, maxResults)
      return [results: parsed, reachError: false, reachErrorDetail: '']
    } catch (Throwable t) {
      log.error('WebSearch DuckDuckGo failed endpoint={} query={}: {}', endpoint, q, t.message, t)
      return [results: [], reachError: true, reachErrorDetail: t.message ?: t.toString()]
    }
  }

  /**
   * Parse duck duck go lite html.
   * @param html Caller-supplied input.
   * @param maxResults Caller-supplied input.
   * @return List<Map> result.
   */
  private static List<Map> parseDuckDuckGoLiteHtml(String html, int maxResults) {
    List<Map> results = []
    if (!html?.trim() || maxResults < 1) {
      return results
    }
    java.util.regex.Pattern linkPat =
      java.util.regex.Pattern.compile("(?is)<a[^>]*class=['\"]result-link['\"][^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>")
    java.util.regex.Pattern snippetPat =
      java.util.regex.Pattern.compile("(?is)<td[^>]*class=['\"]result-snippet['\"][^>]*>(.*?)</td>")
    def linkMatcher = linkPat.matcher(html)
    def snippetMatcher = snippetPat.matcher(html)
    while (linkMatcher.find() && results.size() < maxResults) {
      String url = WebSearchResultTextUtil.decodeHtmlEntities(linkMatcher.group(1)?.trim())
      String title = WebSearchResultTextUtil.stripHtml(linkMatcher.group(2))
      String snippet = ''
      if (snippetMatcher.find()) {
        snippet = WebSearchResultTextUtil.stripHtml(snippetMatcher.group(1))
      }
      if (!url || WebSearchResultTextUtil.skipResultUrl(url)) {
        continue
      }
      results.add([title: title ?: url, url: url, snippet: snippet])
    }
    results
  }

  /**
   * Parse duck duck go html.
   * @param html Caller-supplied input.
   * @param maxResults Caller-supplied input.
   * @return List<Map> result.
   */
  private static List<Map> parseDuckDuckGoHtml(String html, int maxResults) {
    List<Map> results = []
    if (!html?.trim() || maxResults < 1) {
      return results
    }
    java.util.regex.Pattern linkPat =
      java.util.regex.Pattern.compile("(?is)<a[^>]*class=['\"]result__a['\"][^>]*href=['\"]([^'\"]+)['\"][^>]*>(.*?)</a>")
    java.util.regex.Pattern snippetPat =
      java.util.regex.Pattern.compile("(?is)<a[^>]*class=['\"]result__snippet['\"][^>]*>(.*?)</a>")
    def linkMatcher = linkPat.matcher(html)
    def snippetMatcher = snippetPat.matcher(html)
    while (linkMatcher.find() && results.size() < maxResults) {
      String url = WebSearchResultTextUtil.decodeHtmlEntities(linkMatcher.group(1)?.trim())
      String title = WebSearchResultTextUtil.stripHtml(linkMatcher.group(2))
      String snippet = snippetMatcher.find() ? WebSearchResultTextUtil.stripHtml(snippetMatcher.group(1)) : ''
      if (!url || WebSearchResultTextUtil.skipResultUrl(url)) {
        continue
      }
      results.add([title: title ?: url, url: url, snippet: snippet])
    }
    results
  }
}
